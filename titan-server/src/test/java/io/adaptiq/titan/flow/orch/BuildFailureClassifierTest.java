package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.orch.BuildFailureClassifier.Classification;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link BuildFailureClassifier} (issue #1105). Exercises the pure {@link
 * BuildFailureClassifier#classify} against one fixture log per {@link FailureCause} kind, the
 * ambiguous → {@link FailureCause#UNKNOWN} fallthrough, and adversarial sad paths (empty log, null
 * lines, ordering precedence, malformed signature config).
 *
 * <p>Runs against the REAL {@code /failure-signatures.yaml} via {@link
 * BuildFailureClassifier#defaultInstance()} so a typo in the shipped signatures file fails this
 * test, not production.
 */
class BuildFailureClassifierTest {

  private final BuildFailureClassifier classifier = BuildFailureClassifier.defaultInstance();

  // ── Happy path: one fixture log per cause kind (test-matrix requirement) ────

  @Test
  void test_classify_oom_log_returns_oom_with_snippet() {
    Classification c =
        classifier.classify(
            List.of(
                "[INFO] Running integration tests",
                "Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space",
                "[INFO] Build step failed"));
    assertEquals(FailureCause.OOM, c.cause());
    assertNotNull(c.snippet());
    assertTrue(c.snippet().contains("OutOfMemoryError"), "snippet names the matching line");
  }

  @Test
  void test_classify_compile_error_log_returns_compile_error() {
    Classification c =
        classifier.classify(
            List.of(
                "[INFO] Compiling 42 source files",
                "[ERROR] COMPILATION ERROR :",
                "[ERROR] Foo.java:[12,5] error: cannot find symbol"));
    assertEquals(FailureCause.COMPILE_ERROR, c.cause());
    assertTrue(c.snippet().contains("COMPILATION ERROR"));
  }

  @Test
  void test_classify_test_failure_log_returns_test_failure() {
    Classification c =
        classifier.classify(
            List.of(
                "[INFO] Running io.adaptiq.FooTest",
                "Tests run: 12, Failures: 3, Errors: 0, Skipped: 0",
                "There were failing tests. See the report for details."));
    assertEquals(FailureCause.TEST_FAILURE, c.cause());
  }

  @Test
  void test_classify_timeout_log_returns_timeout() {
    Classification c =
        classifier.classify(
            List.of(
                "[INFO] Still running step 'deploy'",
                "Build timed out (after 30 minutes). Marking build as failed."));
    assertEquals(FailureCause.TIMEOUT, c.cause());
  }

  @Test
  void test_classify_network_log_returns_network() {
    Classification c =
        classifier.classify(
            List.of(
                "Cloning into 'repo'...",
                "fatal: unable to access 'https://github.com/acme/repo': Could not resolve host: github.com"));
    assertEquals(FailureCause.NETWORK, c.cause());
  }

  @Test
  void test_classify_rate_limit_log_returns_rate_limit() {
    Classification c =
        classifier.classify(
            List.of(
                "Fetching pull request metadata",
                "remote: API rate limit exceeded for installation ID 4711."));
    assertEquals(FailureCause.RATE_LIMIT, c.cause());
  }

  // ── Adversarial: ambiguous + degenerate inputs ─────────────────────────────

  @Test
  void test_classify_ambiguous_log_returns_unknown_with_null_snippet() {
    Classification c =
        classifier.classify(
            List.of(
                "[INFO] Starting step 'package'",
                "[INFO] step exited with code 7",
                "[INFO] cleaning up workspace"));
    assertEquals(FailureCause.UNKNOWN, c.cause());
    assertNull(c.snippet(), "UNKNOWN carries no snippet");
  }

  @Test
  void test_classify_empty_log_returns_unknown() {
    assertEquals(FailureCause.UNKNOWN, classifier.classify(List.of()).cause());
  }

  @Test
  void test_classify_tolerates_null_and_blank_lines() {
    List<String> lines = java.util.Arrays.asList(null, "", "   ", "java.lang.OutOfMemoryError");
    assertEquals(FailureCause.OOM, classifier.classify(lines).cause());
  }

  @Test
  void test_classify_oom_wins_over_test_failure_when_both_present() {
    // Ordering precedence: an OOM that also tripped a downstream test failure must classify as the
    // root cause (OOM), not the symptom (test_failure). Regression guard for signature ordering.
    Classification c =
        classifier.classify(
            List.of(
                "There were failing tests. See the report for details.",
                "java.lang.OutOfMemoryError: GC overhead limit exceeded"));
    assertEquals(FailureCause.OOM, c.cause());
  }

  // ── Exit-code awareness (acceptance: "log + exit code") ─────────────────────

  @Test
  void test_classify_exit_137_with_no_log_signature_returns_oom() {
    // The OOM-killer reaped the container (exit 137) leaving no OutOfMemoryError line — the exit
    // code is the only signal, and must still classify as OOM rather than unknown.
    Classification c =
        classifier.classify(
            List.of("[INFO] Running integration tests", "[INFO] step exited with code 137"), 137);
    assertEquals(FailureCause.OOM, c.cause());
    assertNotNull(c.snippet());
    assertTrue(c.snippet().contains("137"), "exit-code snippet names the code");
  }

  @Test
  void test_classify_log_text_outranks_exit_code() {
    // A network failure in the log alongside exit 137 classifies on the more-informative log line,
    // not the exit code — log text always wins.
    Classification c =
        classifier.classify(List.of("fatal: Could not resolve host: github.com"), 137);
    assertEquals(FailureCause.NETWORK, c.cause());
  }

  @Test
  void test_classify_unmapped_exit_code_returns_unknown() {
    Classification c = classifier.classify(List.of("[INFO] step exited with code 7"), 7);
    assertEquals(FailureCause.UNKNOWN, c.cause());
    assertNull(c.snippet());
  }

  // ── Config validation: malformed signatures fail loud ──────────────────────

  @Test
  void test_fromYaml_unknown_cause_key_throws() {
    String yaml = "signatures:\n  - cause: not_a_real_cause\n    patterns:\n      - \"boom\"\n";
    assertThrows(IllegalArgumentException.class, () -> fromYaml(yaml));
  }

  @Test
  void test_fromYaml_invalid_regex_throws() {
    String yaml = "signatures:\n  - cause: oom\n    patterns:\n      - \"[unclosed\"\n";
    assertThrows(IllegalStateException.class, () -> fromYaml(yaml));
  }

  @Test
  void test_fromYaml_empty_signatures_throws() {
    assertThrows(IllegalStateException.class, () -> fromYaml("signatures: []\n"));
  }

  @Test
  void test_failureCause_fromWire_rejects_garbage() {
    assertThrows(IllegalArgumentException.class, () -> FailureCause.fromWire("totally_bogus"));
  }

  private static BuildFailureClassifier fromYaml(String yaml) {
    return BuildFailureClassifier.fromYaml(
        new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
  }
}
