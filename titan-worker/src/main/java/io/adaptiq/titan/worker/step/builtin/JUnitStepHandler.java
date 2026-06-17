package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ArtifactSink;
import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.TestResultSink;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.tools.ant.DirectoryScanner;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The built-in {@code junit} step — parses JUnit / surefire XML reports from the workspace and
 * publishes a pass/fail/skip tally as step outputs (the migration-frequency step right after {@code
 * sh}/{@code git}, design/32 §12).
 *
 * <p>Titan's run status is binary — {@code SUCCESS} / {@code FAILED}, there is no {@code UNSTABLE}
 * (design/26) — so this handler makes the faithful choice: a green node that swallowed a failing
 * test is worse than a red one, therefore <strong>a test failure fails the step</strong>. The
 * escape hatch is {@code skipMarkingBuildUnstable: true}, which keeps the step green and leaves the
 * tally to a downstream gate.
 *
 * <p>The report tally is published as outputs — {@code total}, {@code passed}, {@code failed},
 * {@code skipped}, {@code suites} — so a precondition can gate on {@code ${{
 * steps['test'].outputs.failed }} == 0} regardless of the step's own status.
 *
 * <p>Glob expansion is Ant's {@link DirectoryScanner} — the same engine {@code archiveArtifacts}
 * uses, so the {@code **}/{@code *}/{@code ?} patterns behave consistently. XML parsing runs with
 * DTDs and external entities disabled (XXE-safe): a test report is untrusted input.
 *
 * <p>Idempotent (design/32 §4): a reaped, re-run step simply re-reads the same reports and
 * re-publishes the same tally.
 *
 * <p>Each matched report XML is additionally archived through the {@link ArtifactSink} under the
 * {@code titan/test-reports/} name prefix. The server reads those archived reports back to render
 * the Test Result page and pass/fail trend graph for the build. Archiving is purely additive —
 * best-effort: a missing or {@code UNCONFIGURED} sink never fails the step (the tally, the outputs
 * and the fail-on-failure policy are unaffected).
 */
public final class JUnitStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "junit";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "junit",
        "Publish JUnit test results",
        "Parses JUnit/surefire XML reports matching an Ant-style glob and publishes a "
            + "pass/fail/skip tally as step outputs. A test failure fails the step "
            + "unless 'skipMarkingBuildUnstable' is set.",
        List.of(
            ParamSpec.required(
                "testResults",
                "string",
                "Ant-style glob(s) for the JUnit XML reports — comma- or "
                    + "space-separated, e.g. '**/surefire-reports/*.xml'."),
            ParamSpec.optional(
                "allowEmptyResults",
                "boolean",
                "If true, a step that matches no report files succeeds; "
                    + "otherwise it fails (default false)."),
            ParamSpec.optional(
                "skipMarkingBuildUnstable",
                "boolean",
                "If true, the step succeeds even when tests failed — only the "
                    + "tally is published (default false).")),
        // design/42 §4.6: the scalar shorthand `junit: '<glob>'` resolves to `testResults`.
        "testResults");
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    // Accept the explicit `testResults:` key and the scalar shorthand (parser stores `value`).
    String includes = request.argString("testResults");
    if (includes == null || includes.isBlank()) {
      includes = request.argString("value");
    }
    if (includes == null || includes.isBlank()) {
      return StepResult.failed("junit: 'testResults' is required");
    }
    boolean allowEmptyResults = request.argBoolean("allowEmptyResults", false);
    boolean skipMarkingBuildUnstable = request.argBoolean("skipMarkingBuildUnstable", false);

    File baseDir = request.workDir().toFile();
    if (!baseDir.isDirectory()) {
      return StepResult.failed("junit: workspace directory does not exist: " + baseDir);
    }

    DirectoryScanner scanner = new DirectoryScanner();
    scanner.setBasedir(baseDir);
    scanner.setIncludes(splitPatterns(includes));
    scanner.scan();
    String[] matched = scanner.getIncludedFiles();

    if (matched.length == 0) {
      String message = "junit: no test report files matched '" + includes + "'";
      if (allowEmptyResults) {
        request.log().system(message + " — allowEmptyResults is set, continuing");
        publish(request, new Tally());
        return StepResult.success();
      }
      return StepResult.failed(message);
    }

    Tally tally = new Tally();
    List<TestResultSink.Case> cases = new ArrayList<>();
    DocumentBuilder builder = secureDocumentBuilder();
    for (String relative : matched) {
      Path file = request.workDir().resolve(relative);
      try {
        countReport(builder.parse(file.toFile()), tally, cases);
      } catch (Exception e) {
        // A single unreadable report is a step failure — a silently dropped report file
        // would understate the failure count and let a broken build look green.
        return StepResult.failed(
            "junit: could not parse test report '"
                + relative.replace(File.separatorChar, '/')
                + "': "
                + e.getMessage());
      }
    }

    // Archive each matched report so the server can render the Test Result page + trend.
    // Purely additive and best-effort — the tally
    // and the fail-on-failure policy below are the contract; archiving never changes them.
    archiveReports(request, matched);

    // Persist per-case rows through the TestResultSink (issue #298) so the standalone
    // controller's REST API + UI test-results panel can read them without round-tripping
    // through the archived XMLs. Additive and best-effort — same contract as archiving:
    // a persistence failure must not fail a step whose tests parsed cleanly.
    publishCases(request, cases);

    publish(request, tally);
    request
        .log()
        .system(
            "junit: "
                + tally.suites
                + " suite(s), "
                + tally.total
                + " test(s) — "
                + tally.passed
                + " passed, "
                + tally.failed
                + " failed, "
                + tally.skipped
                + " skipped");

    if (tally.failed > 0 && !skipMarkingBuildUnstable) {
      // Log the reason as well as returning it: the engine renders a FAILED step's console
      // line generically ("Step exited …"), so a step that wants its failure explained in
      // its own log must say so itself.
      String reason =
          "junit: "
              + tally.failed
              + " test(s) failed — failing the step "
              + "(set skipMarkingBuildUnstable: true to keep the build green)";
      request.log().system(reason);
      return StepResult.failed(reason);
    }
    if (tally.failed > 0) {
      request
          .log()
          .system(
              "junit: "
                  + tally.failed
                  + " test(s) failed — skipMarkingBuildUnstable is set, "
                  + "the step is reported green");
    }
    return StepResult.success();
  }

  /**
   * The archive-name prefix every report XML this step archives lands under. The server keys off
   * exactly this prefix to find the reports to render.
   */
  public static final String REPORT_ARCHIVE_PREFIX = "titan/test-reports/";

  /**
   * Archive each matched report XML through the {@link ArtifactSink}, under {@link
   * #REPORT_ARCHIVE_PREFIX} + the report's workspace-relative path. Best-effort: if no store is
   * wired ({@link ArtifactSink#UNCONFIGURED}) or a single archive call fails, it is logged and
   * skipped — archiving must never fail a step whose tests parsed cleanly.
   */
  private static void archiveReports(StepRequest request, String[] matched) {
    for (String relative : matched) {
      // DirectoryScanner yields platform-separator paths; an archive name is always
      // forward-slash (same convention as archiveArtifacts).
      String name = REPORT_ARCHIVE_PREFIX + relative.replace(File.separatorChar, '/');
      try {
        request.artifacts().archive(name, request.workDir().resolve(relative), false);
      } catch (IOException e) {
        request
            .log()
            .system(
                "junit: could not archive test report '"
                    + name
                    + "' ("
                    + e.getMessage()
                    + ") — the Test Result page may be incomplete");
      }
    }
  }

  /**
   * Submit the parsed per-case rows through the {@link TestResultSink} (issue #298). Best-effort:
   * if no sink is wired ({@link TestResultSink#UNCONFIGURED} is a silent no-op) or the submit call
   * throws, it is logged and swallowed — the step's tally + fail-on-failure policy are the
   * contract, persistence is a bonus.
   */
  private static void publishCases(StepRequest request, List<TestResultSink.Case> cases) {
    try {
      request.testReports().submit(cases);
    } catch (IOException e) {
      request
          .log()
          .system(
              "junit: could not persist "
                  + cases.size()
                  + " test-case row(s) ("
                  + e.getMessage()
                  + ") — the build's test-results panel may be incomplete");
    }
  }

  /** Publish the tally as step outputs (always — even on a failed step, the counts are useful). */
  private static void publish(StepRequest request, Tally tally) {
    request.outputs().put("suites", tally.suites);
    request.outputs().put("total", tally.total);
    request.outputs().put("passed", tally.passed);
    request.outputs().put("failed", tally.failed);
    request.outputs().put("skipped", tally.skipped);
  }

  /**
   * Tally one parsed report document into {@code tally} <em>and</em> emit one {@link
   * TestResultSink.Case} per {@code <testcase>} into {@code cases} (issue #298). Counts {@code
   * <testcase>} elements directly rather than trusting the {@code <testsuite>} summary attributes —
   * a {@code <testcase>} with a {@code <failure>} or {@code <error>} child is a failure, one with a
   * {@code <skipped>} child is skipped, anything else passed. This is robust to reports that omit
   * the summary attributes.
   */
  private static void countReport(Document doc, Tally tally, List<TestResultSink.Case> cases) {
    NodeList suites = doc.getElementsByTagName("testsuite");
    tally.suites += suites.getLength();

    NodeList caseNodes = doc.getElementsByTagName("testcase");
    for (int i = 0; i < caseNodes.getLength(); i++) {
      Element testCase = (Element) caseNodes.item(i);
      tally.total++;
      String status;
      String failureMessage = null;
      Element failureEl = firstChild(testCase, "failure");
      Element errorEl = firstChild(testCase, "error");
      if (failureEl != null || errorEl != null) {
        tally.failed++;
        status = "FAILED";
        Element which = failureEl != null ? failureEl : errorEl;
        failureMessage = renderFailure(which);
      } else if (hasChild(testCase, "skipped")) {
        tally.skipped++;
        status = "SKIPPED";
      } else {
        tally.passed++;
        status = "PASSED";
      }
      cases.add(
          new TestResultSink.Case(
              enclosingSuiteName(testCase),
              attr(testCase, "classname"),
              attr(testCase, "name"),
              status,
              parseTimeMs(attr(testCase, "time")),
              failureMessage));
    }
  }

  /**
   * Render a {@code <failure>}/{@code <error>} element into a single-string {@code failure_message}
   * — the {@code message=…} attribute first, then a blank line, then the element's text body
   * (typically the stack trace). Truncated to 64 KiB to keep one rogue stack from blowing up the
   * row; the cap is documented in the column comment.
   */
  private static String renderFailure(Element el) {
    String message = attr(el, "message");
    String body = el.getTextContent() == null ? "" : el.getTextContent().trim();
    StringBuilder sb = new StringBuilder();
    if (message != null && !message.isEmpty()) {
      sb.append(message);
    }
    if (!body.isEmpty()) {
      if (sb.length() > 0) {
        sb.append("\n\n");
      }
      sb.append(body);
    }
    String out = sb.toString();
    final int cap = 64 * 1024;
    if (out.length() > cap) {
      return out.substring(0, cap) + "\n…[truncated]";
    }
    return out;
  }

  /** Best-effort enclosing {@code <testsuite name=…>}, or {@code ""} if not under one. */
  private static String enclosingSuiteName(Element testCase) {
    Node p = testCase.getParentNode();
    while (p != null && p.getNodeType() == Node.ELEMENT_NODE) {
      if ("testsuite".equals(p.getNodeName())) {
        String n = ((Element) p).getAttribute("name");
        return n == null ? "" : n;
      }
      p = p.getParentNode();
    }
    return "";
  }

  /** An attribute value, or {@code ""} when absent — never {@code null}. */
  private static String attr(Element el, String name) {
    String v = el.getAttribute(name);
    return v == null ? "" : v;
  }

  /**
   * Parse a JUnit {@code time="…"} attribute (seconds, optionally fractional) into milliseconds.
   * Returns {@code 0} when the attribute is absent or unparseable — the column default.
   */
  private static long parseTimeMs(String time) {
    if (time == null || time.isEmpty()) {
      return 0L;
    }
    try {
      double seconds = Double.parseDouble(time);
      if (seconds < 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) {
        return 0L;
      }
      return Math.round(seconds * 1000.0);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  /** First direct child element with the given tag name, or {@code null}. */
  private static Element firstChild(Element element, String tagName) {
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
        return (Element) child;
      }
    }
    return null;
  }

  /** Whether {@code element} has a direct child element with the given tag name. */
  private static boolean hasChild(Element element, String tagName) {
    NodeList children = element.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && tagName.equals(child.getNodeName())) {
        return true;
      }
    }
    return false;
  }

  /** A {@link DocumentBuilder} hardened against XXE — a test report is untrusted input. */
  private static DocumentBuilder secureDocumentBuilder() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setExpandEntityReferences(false);
    factory.setXIncludeAware(false);
    return factory.newDocumentBuilder();
  }

  /**
   * Split a comma- / whitespace-separated Ant glob string into a pattern array. A blank input
   * yields an empty array.
   */
  private static String[] splitPatterns(String raw) {
    if (raw == null || raw.isBlank()) {
      return new String[0];
    }
    return Arrays.stream(raw.split("[,\\s]+"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
  }

  /** A running pass/fail/skip count across every parsed report. */
  private static final class Tally {
    private int suites;
    private int total;
    private int passed;
    private int failed;
    private int skipped;
  }
}
