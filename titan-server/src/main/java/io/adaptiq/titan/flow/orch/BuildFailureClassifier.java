package io.adaptiq.titan.flow.orch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.FlowNodeConsole;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Heuristic build-failure root-cause classifier (issue #1105). Given a failed build, it loads the
 * failing flow-node's console log, scans it against an ordered, externally-configurable set of
 * regex signatures ({@code failure-signatures.yaml}), and records the first matching {@link
 * FailureCause} (plus the matching log line) on {@code titan.builds.failure_cause} — so operators
 * see <em>why</em> a build went red without scrolling logs.
 *
 * <p><b>Async, best-effort, non-blocking.</b> {@link #classifyAndStore} is invoked from {@link
 * BuildCloser} on a daemon thread <em>after</em> the build's terminal transition has been
 * persisted, so a slow or failing classification never delays the build closing or double-fires a
 * side effect. Every failure path inside {@code classifyAndStore} is swallowed-and-logged — a
 * classifier crash must never escalate into a build-state corruption.
 *
 * <p><b>Signatures are ordered most-specific-first.</b> The first signature whose <em>any</em>
 * pattern matches <em>any</em> log line wins; the matched line becomes the {@code
 * failure_cause_detail} tooltip snippet. No signature matching → {@link FailureCause#UNKNOWN} with
 * a {@code null} snippet.
 */
public final class BuildFailureClassifier {

  private static final Logger LOGGER = Logger.getLogger(BuildFailureClassifier.class.getName());

  /** Shared mapper for parsing the failing node's {@code resultJson} (e.g. {@code exitCode}). */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Classpath location of the default signature set. */
  static final String SIGNATURES_RESOURCE = "/failure-signatures.yaml";

  /** Hard cap on the stored snippet so a pathological single-line log can't bloat the row. */
  private static final int MAX_SNIPPET_LEN = 500;

  /** Lazily-loaded process-wide default, parsed once from the classpath resource. Immutable. */
  @Nullable private static volatile BuildFailureClassifier defaultInstance;

  private final List<CompiledSignature> signatures;

  BuildFailureClassifier(@NonNull List<CompiledSignature> signatures) {
    this.signatures = List.copyOf(signatures);
  }

  /**
   * The process-wide classifier parsed from {@code /failure-signatures.yaml} on the classpath.
   * Parsed once and cached. A malformed signatures file is a boot-class configuration error and
   * fails loud (the manifesto's "boot-time validation fails LOUD" rule) the first time the
   * classifier is needed.
   */
  @NonNull
  public static BuildFailureClassifier defaultInstance() {
    BuildFailureClassifier local = defaultInstance;
    if (local == null) {
      synchronized (BuildFailureClassifier.class) {
        local = defaultInstance;
        if (local == null) {
          local = loadFromClasspath();
          defaultInstance = local;
        }
      }
    }
    return local;
  }

  private static BuildFailureClassifier loadFromClasspath() {
    try (InputStream in = BuildFailureClassifier.class.getResourceAsStream(SIGNATURES_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(
            "failure-signatures.yaml not found on classpath at " + SIGNATURES_RESOURCE);
      }
      return fromYaml(in);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Failed reading " + SIGNATURES_RESOURCE, e);
    }
  }

  /**
   * Parse a signatures YAML document into a classifier. Visible for tests so a fixture signature
   * set can be supplied deterministically. A {@code cause:} key that is not a {@link FailureCause}
   * wire name, or an un-compilable regex, throws — config errors fail loud, never silently degrade.
   */
  @NonNull
  static BuildFailureClassifier fromYaml(@NonNull InputStream yaml) {
    SignatureFile parsed;
    try {
      parsed = new ObjectMapper(new YAMLFactory()).readValue(yaml, SignatureFile.class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("failure-signatures.yaml is not valid YAML", e);
    }
    if (parsed == null || parsed.signatures == null || parsed.signatures.isEmpty()) {
      throw new IllegalStateException("failure-signatures.yaml declares no signatures");
    }
    List<CompiledSignature> compiled = new ArrayList<>(parsed.signatures.size());
    for (RawSignature raw : parsed.signatures) {
      FailureCause cause = FailureCause.fromWire(raw.cause); // throws on a typo'd cause key
      if (raw.patterns == null || raw.patterns.isEmpty()) {
        throw new IllegalStateException("signature for cause '" + raw.cause + "' has no patterns");
      }
      List<Pattern> patterns = new ArrayList<>(raw.patterns.size());
      for (String p : raw.patterns) {
        try {
          patterns.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
        } catch (PatternSyntaxException pse) {
          throw new IllegalStateException(
              "signature for cause '" + raw.cause + "' has an invalid regex: " + p, pse);
        }
      }
      List<Integer> exitCodes = raw.exitCodes == null ? List.of() : List.copyOf(raw.exitCodes);
      compiled.add(new CompiledSignature(cause, patterns, exitCodes));
    }
    return new BuildFailureClassifier(compiled);
  }

  /**
   * Log-text-only classification (no exit code available). See {@link #classify(List, Integer)}.
   */
  @NonNull
  public Classification classify(@NonNull List<String> logLines) {
    return classify(logLines, null);
  }

  /**
   * Classify a failing step from its log lines <em>and</em> exit code (issue #1105 acceptance: "log
   * + exit code"). Pure function — no I/O — so it is unit-testable against fixture logs. Scans
   * signatures in declared order; the first signature whose any pattern matches any line wins, and
   * the matched line is returned as the snippet (truncated to {@value #MAX_SNIPPET_LEN} chars). If
   * <em>no</em> log pattern matches, the {@code exitCode} is tried as a fallback: the first
   * signature listing it under {@code exit_codes:} wins (e.g. a container OOMKilled with exit 137
   * but no {@code OutOfMemoryError} log line). Log text always outranks the exit code because the
   * matching line is the more informative tooltip. No match either way → {@link
   * FailureCause#UNKNOWN} with a {@code null} snippet.
   *
   * @param logLines the failing node's console lines; an empty or all-null list defers to the code.
   * @param exitCode the failing node's process exit code, or {@code null} if unknown.
   */
  @NonNull
  public Classification classify(@NonNull List<String> logLines, @Nullable Integer exitCode) {
    for (CompiledSignature sig : signatures) {
      for (String line : logLines) {
        if (line == null || line.isEmpty()) {
          continue;
        }
        for (Pattern p : sig.patterns) {
          if (p.matcher(line).find()) {
            return new Classification(sig.cause, truncate(line));
          }
        }
      }
    }
    if (exitCode != null) {
      for (CompiledSignature sig : signatures) {
        if (sig.exitCodes.contains(exitCode)) {
          return new Classification(sig.cause, "exit code " + exitCode);
        }
      }
    }
    return new Classification(FailureCause.UNKNOWN, null);
  }

  /**
   * Resolve the failing build's first FAILED flow-node, classify its console log, and persist the
   * verdict on the build row. Best-effort: any failure (no failed node, log read error, DB write
   * error) is logged at WARNING/FINE and swallowed — this runs async after the terminal transition
   * and must never crash the orchestrator.
   *
   * @param stores engine data-access façade.
   * @param buildId the FAILED build to diagnose.
   */
  public void classifyAndStore(@NonNull TitanStores stores, long buildId) {
    try {
      FlowNodeRow failedNode = firstFailedNode(stores, buildId);
      List<String> lines =
          failedNode == null
              ? List.of()
              : FlowNodeConsole.nodeLogLines(stores, buildId, failedNode.nodeId);
      Integer exitCode = failedNode == null ? null : exitCodeFrom(failedNode.resultJson);
      Classification result = classify(lines, exitCode);
      stores.builds().updateFailureCause(buildId, result.cause().wire(), result.snippet());
      LOGGER.log(
          Level.INFO,
          "[titan] build {0} failure classified: {1}",
          new Object[] {buildId, result.cause().wire()});
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] build {0}: failure classification failed (swallowed): {1}",
          new Object[] {buildId, e.getMessage()});
    }
  }

  @Nullable
  private static FlowNodeRow firstFailedNode(@NonNull TitanStores stores, long buildId) {
    // Reuse the data-layer query (ordered by node_id) instead of loading every node row and
    // filtering in Java — only the FAILED rows come back.
    List<FlowNodeRow> failed = stores.flowNodes().listByBuildAndStatus(buildId, "FAILED");
    return failed.isEmpty() ? null : failed.get(0);
  }

  /**
   * Best-effort {@code exitCode} extraction from a node's {@code resultJson} (e.g. {@code 137}).
   */
  @Nullable
  private static Integer exitCodeFrom(@Nullable String resultJson) {
    if (resultJson == null || resultJson.isBlank()) {
      return null;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode ec = JSON.readTree(resultJson).get("exitCode");
      return ec != null && ec.isNumber() ? ec.intValue() : null;
    } catch (com.fasterxml.jackson.core.JacksonException e) {
      return null;
    }
  }

  @Nullable
  private static String truncate(@Nullable String s) {
    if (s == null) {
      return null;
    }
    String trimmed = s.strip();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() <= MAX_SNIPPET_LEN ? trimmed : trimmed.substring(0, MAX_SNIPPET_LEN);
  }

  /** The verdict: a cause plus the log line that proved it ({@code null} for UNKNOWN). */
  public record Classification(@NonNull FailureCause cause, @Nullable String snippet) {}

  /** A cause bound to its compiled, case-insensitive patterns and (optional) exit codes. */
  static final class CompiledSignature {
    final FailureCause cause;
    final List<Pattern> patterns;
    final List<Integer> exitCodes;

    CompiledSignature(FailureCause cause, List<Pattern> patterns, List<Integer> exitCodes) {
      this.cause = cause;
      this.patterns = patterns;
      this.exitCodes = exitCodes;
    }
  }

  // ── YAML binding shapes (Jackson) ───────────────────────────────────────────
  static final class SignatureFile {
    public List<RawSignature> signatures;
  }

  static final class RawSignature {
    public String cause;
    public List<String> patterns;

    /** Optional exit codes that imply this cause when no log pattern matched (e.g. 137 → oom). */
    @com.fasterxml.jackson.annotation.JsonProperty("exit_codes")
    public List<Integer> exitCodes;

    // Jackson tolerance: ignore any future keys we don't model yet.
    @com.fasterxml.jackson.annotation.JsonAnySetter
    void ignore(String key, Object value) {
      // no-op
    }
  }
}
