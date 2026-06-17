package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.TestResultSink;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link JUnitStepHandler} — the shared TCK plus {@code junit}-specifics: the report tally, the
 * fail-on-failure policy and its {@code skipMarkingBuildUnstable} escape hatch, the empty-match
 * policy, the scalar shorthand, and XXE-safe parsing.
 */
class JUnitStepHandlerTest extends StepHandlerTck {

  @Override
  protected StepHandler newHandler() {
    return new JUnitStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    // The TCK workspace is empty; an empty match with allowEmptyResults is the valid success.
    return Map.of("testResults", "**/*.xml", "allowEmptyResults", true);
  }

  // ── helpers ───────────────────────────────────────────────────────────

  private void write(String relativePath, String content) throws IOException {
    var target = workDir.resolve(relativePath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
  }

  private StepHandlerTck.CapturingOutputs outputs;
  private StepHandlerTck.CapturingArtifacts artifacts;
  private StepHandlerTck.CapturingTestReports testReports;

  private StepResult run(Map<String, Object> args) throws Exception {
    outputs = new StepHandlerTck.CapturingOutputs();
    artifacts = new StepHandlerTck.CapturingArtifacts();
    testReports = new StepHandlerTck.CapturingTestReports();
    StepRequest request =
        new StepRequest(
            "junit",
            args,
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            new StepHandlerTck.CapturingLog(),
            outputs,
            artifacts,
            testReports);
    return new JUnitStepHandler().execute(request);
  }

  /** A surefire-shaped report: one suite, the given case outcomes. */
  private static String report(String suiteName, String... outcomes) {
    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<testsuite name=\"").append(suiteName).append("\">\n");
    int i = 0;
    for (String outcome : outcomes) {
      sb.append("  <testcase name=\"t")
          .append(i++)
          .append("\" classname=\"")
          .append(suiteName)
          .append("\">");
      switch (outcome) {
        case "fail" -> sb.append("<failure message=\"boom\">stack</failure>");
        case "error" -> sb.append("<error message=\"kaboom\">stack</error>");
        case "skip" -> sb.append("<skipped/>");
        default -> {
          /* pass — no child element */
        }
      }
      sb.append("</testcase>\n");
    }
    sb.append("</testsuite>\n");
    return sb.toString();
  }

  // ── behaviour ─────────────────────────────────────────────────────────

  @Test
  void talliesPassesFailuresErrorsAndSkips() throws Exception {
    write(
        "target/surefire-reports/TEST-a.xml", report("a", "pass", "pass", "fail", "error", "skip"));

    StepResult result =
        run(Map.of("testResults", "**/TEST-*.xml", "skipMarkingBuildUnstable", true));

    assertTrue(result.isSuccess(), result.message());
    assertEquals(5, outputs.map.get("total"));
    assertEquals(2, outputs.map.get("passed"));
    assertEquals(2, outputs.map.get("failed"));
    assertEquals(1, outputs.map.get("skipped"));
    assertEquals(1, outputs.map.get("suites"));
  }

  @Test
  void aTestFailureFailsTheStepByDefault() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("a", "pass", "fail"));

    StepResult result = run(Map.of("testResults", "**/TEST-*.xml"));

    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("1 test"), result.message());
    // The tally is still published — a failed step keeps its outputs.
    assertEquals(1, outputs.map.get("failed"));
  }

  @Test
  void skipMarkingBuildUnstableKeepsTheStepGreenDespiteFailures() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("a", "fail"));

    StepResult result =
        run(Map.of("testResults", "**/TEST-*.xml", "skipMarkingBuildUnstable", true));

    assertTrue(result.isSuccess(), result.message());
    assertEquals(1, outputs.map.get("failed"));
  }

  @Test
  void allGreenReportSucceeds() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("a", "pass", "pass"));

    StepResult result = run(Map.of("testResults", "**/TEST-*.xml"));

    assertTrue(result.isSuccess(), result.message());
    assertEquals(2, outputs.map.get("passed"));
    assertEquals(0, outputs.map.get("failed"));
  }

  @Test
  void sumsAcrossMultipleReportFiles() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("a", "pass", "fail"));
    write("target/surefire-reports/TEST-b.xml", report("b", "pass", "pass", "skip"));

    StepResult result =
        run(Map.of("testResults", "**/TEST-*.xml", "skipMarkingBuildUnstable", true));

    assertTrue(result.isSuccess(), result.message());
    assertEquals(5, outputs.map.get("total"));
    assertEquals(3, outputs.map.get("passed"));
    assertEquals(1, outputs.map.get("failed"));
    assertEquals(1, outputs.map.get("skipped"));
    assertEquals(2, outputs.map.get("suites"));
  }

  @Test
  void noMatchFailsByDefault() throws Exception {
    StepResult result = run(Map.of("testResults", "**/none-*.xml"));

    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("no test report"), result.message());
  }

  @Test
  void noMatchSucceedsWhenAllowEmptyResultsIsSet() throws Exception {
    StepResult result = run(Map.of("testResults", "**/none-*.xml", "allowEmptyResults", true));

    assertTrue(result.isSuccess(), result.message());
    assertEquals(0, outputs.map.get("total"));
  }

  @Test
  void acceptsTheScalarShorthandGlob() throws Exception {
    // `junit: '**/TEST-*.xml'` — the parser stores the scalar under `value`.
    write("target/surefire-reports/TEST-a.xml", report("a", "pass"));

    StepResult result = run(Map.of("value", "**/TEST-*.xml"));

    assertTrue(result.isSuccess(), result.message());
    assertEquals(1, outputs.map.get("passed"));
  }

  @Test
  void aMissingTestResultsArgumentFails() throws Exception {
    StepResult result = run(Map.of());
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("testResults"), result.message());
  }

  @Test
  void anUnparseableReportFailsTheStep() throws Exception {
    write("target/surefire-reports/TEST-bad.xml", "<testsuite><not-closed>");

    StepResult result = run(Map.of("testResults", "**/TEST-*.xml"));

    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("could not parse"), result.message());
  }

  @Test
  void rejectsAReportThatDeclaresADoctype() throws Exception {
    // XXE hardening: disallow-doctype-decl is on, so any DOCTYPE is a hard parse failure
    // rather than a vector for entity expansion / external file reads.
    write(
        "target/surefire-reports/TEST-xxe.xml",
        "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE testsuite [<!ENTITY x \"y\">]>\n"
            + "<testsuite><testcase name=\"t\"/></testsuite>");

    StepResult result = run(Map.of("testResults", "**/TEST-*.xml"));

    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("could not parse"), result.message());
  }

  @Test
  void archivesEveryMatchedReportUnderTheTestReportsPrefix() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("a", "pass", "fail"));
    write("target/surefire-reports/TEST-b.xml", report("b", "pass"));

    StepResult result =
        run(Map.of("testResults", "**/TEST-*.xml", "skipMarkingBuildUnstable", true));

    assertTrue(result.isSuccess(), result.message());
    List<String> names =
        artifacts.archived.stream()
            .map(StepHandlerTck.CapturingArtifacts.Archived::name)
            .collect(Collectors.toList());
    assertEquals(2, names.size());
    assertTrue(
        names.contains("titan/test-reports/target/surefire-reports/TEST-a.xml"), names.toString());
    assertTrue(
        names.contains("titan/test-reports/target/surefire-reports/TEST-b.xml"), names.toString());
  }

  @Test
  void archivesTheReportBytesVerbatim() throws Exception {
    String xml = report("a", "pass", "fail");
    write("target/surefire-reports/TEST-a.xml", xml);

    run(Map.of("testResults", "**/TEST-*.xml", "skipMarkingBuildUnstable", true));

    assertEquals(1, artifacts.archived.size());
    assertEquals(xml, new String(artifacts.archived.get(0).content(), StandardCharsets.UTF_8));
  }

  @Test
  void archivingIsBestEffortAndNeverFailsAStepWithCleanReports() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("a", "pass"));

    // No artifacts sink supplied — the StepRequest substitutes ArtifactSink.UNCONFIGURED,
    // whose archive() throws. The step must still succeed; archiving is purely additive.
    outputs = new StepHandlerTck.CapturingOutputs();
    StepRequest request =
        new StepRequest(
            "junit",
            Map.of("testResults", "**/TEST-*.xml"),
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            new StepHandlerTck.CapturingLog(),
            outputs);

    StepResult result = new JUnitStepHandler().execute(request);

    assertTrue(result.isSuccess(), result.message());
    assertEquals(1, outputs.map.get("passed"));
  }

  @Test
  void aNoMatchStepArchivesNothing() throws Exception {
    StepResult result = run(Map.of("testResults", "**/none-*.xml", "allowEmptyResults", true));

    assertTrue(result.isSuccess(), result.message());
    assertTrue(artifacts.archived.isEmpty());
  }

  // ── #298: per-case persistence via TestResultSink ─────────────────────

  @Test
  void publishesOneTestResultRowPerTestcaseWithCorrectStatus() throws Exception {
    write(
        "target/surefire-reports/TEST-a.xml", report("a", "pass", "pass", "fail", "error", "skip"));

    StepResult result =
        run(Map.of("testResults", "**/TEST-*.xml", "skipMarkingBuildUnstable", true));

    assertTrue(result.isSuccess(), result.message());
    // submit(rows) is called exactly once per step — one batched DB write per junit step.
    assertEquals(1, testReports.submissions.size());
    assertEquals(5, testReports.cases.size());

    long passed = testReports.cases.stream().filter(c -> "PASSED".equals(c.status())).count();
    long failed = testReports.cases.stream().filter(c -> "FAILED".equals(c.status())).count();
    long skipped = testReports.cases.stream().filter(c -> "SKIPPED".equals(c.status())).count();
    assertEquals(2, passed);
    // <failure> and <error> both map to FAILED on the wire — the controller does not split them.
    assertEquals(2, failed);
    assertEquals(1, skipped);
  }

  @Test
  void carriesSuiteClassnameAndCaseName() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("MySuite", "pass"));

    run(Map.of("testResults", "**/TEST-*.xml"));

    assertEquals(1, testReports.cases.size());
    TestResultSink.Case c = testReports.cases.get(0);
    assertEquals("MySuite", c.suite());
    assertEquals("MySuite", c.className());
    assertEquals("t0", c.name());
  }

  @Test
  void recordsFailureMessageForFailingCasesAndLeavesPassesNull() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("a", "pass", "fail"));

    run(Map.of("testResults", "**/TEST-*.xml", "skipMarkingBuildUnstable", true));

    TestResultSink.Case pass =
        testReports.cases.stream()
            .filter(c -> "PASSED".equals(c.status()))
            .findFirst()
            .orElseThrow();
    TestResultSink.Case fail =
        testReports.cases.stream()
            .filter(c -> "FAILED".equals(c.status()))
            .findFirst()
            .orElseThrow();
    assertNull(pass.failureMessage());
    assertNotNull(fail.failureMessage());
    assertTrue(fail.failureMessage().contains("boom"), fail.failureMessage());
  }

  @Test
  void aSinkFailureNeverFailsAStepWithCleanReports() throws Exception {
    write("target/surefire-reports/TEST-a.xml", report("a", "pass"));

    outputs = new StepHandlerTck.CapturingOutputs();
    artifacts = new StepHandlerTck.CapturingArtifacts();
    TestResultSink throwing =
        cases -> {
          throw new IOException("simulated controller-side persist failure");
        };
    StepRequest request =
        new StepRequest(
            "junit",
            Map.of("testResults", "**/TEST-*.xml"),
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            new StepHandlerTck.CapturingLog(),
            outputs,
            artifacts,
            throwing);

    StepResult result = new JUnitStepHandler().execute(request);

    assertTrue(
        result.isSuccess(),
        "a TestResultSink failure must not fail a step whose tests parsed cleanly: "
            + result.message());
    assertEquals(1, outputs.map.get("passed"));
  }

  @Test
  void countsTestcasesEvenWhenTestsuiteAttributesAreAbsent() throws Exception {
    // No tests/failures attributes on <testsuite> — the handler must count <testcase> nodes.
    write("target/surefire-reports/TEST-a.xml", report("a", "pass", "fail"));

    StepResult result =
        run(Map.of("testResults", "**/TEST-*.xml", "skipMarkingBuildUnstable", true));

    assertTrue(result.isSuccess(), result.message());
    assertEquals(2, outputs.map.get("total"));
  }
}
