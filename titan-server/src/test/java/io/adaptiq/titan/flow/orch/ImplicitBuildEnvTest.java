package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.rows.BuildRow;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Adversarial unit coverage for {@link ImplicitBuildEnv} — the helper that lifts {@code
 * trigger_meta_json} + build identifiers into the implicit env map injected at dispatch (closes
 * #847).
 */
class ImplicitBuildEnvTest {

  @Test
  void surfacesGitCommitAndBranchFromTriggerMetaJson() {
    BuildRow b = build(42L, 7, "{\"commitSha\":\"deadbeef\",\"branch\":\"trunk\"}");
    Map<String, String> env = ImplicitBuildEnv.forBuild(b);
    assertEquals("deadbeef", env.get("GIT_COMMIT"));
    assertEquals("trunk", env.get("GIT_BRANCH"));
    assertEquals("7", env.get("BUILD_NUMBER"));
    assertEquals("42", env.get("BUILD_ID"));
  }

  @Test
  void omitsAbsentFieldsRatherThanEmittingEmptyStrings() {
    // Only commitSha is present — GIT_BRANCH must be absent, not "".
    BuildRow b = build(1L, 1, "{\"commitSha\":\"abc123\"}");
    Map<String, String> env = ImplicitBuildEnv.forBuild(b);
    assertEquals("abc123", env.get("GIT_COMMIT"));
    assertFalse(env.containsKey("GIT_BRANCH"), "GIT_BRANCH must not be present when absent");
  }

  @Test
  void omitsBlankStringValuesFromTriggerMeta() {
    BuildRow b = build(1L, 1, "{\"commitSha\":\"\",\"branch\":\"   \"}");
    Map<String, String> env = ImplicitBuildEnv.forBuild(b);
    assertFalse(env.containsKey("GIT_COMMIT"));
    assertFalse(env.containsKey("GIT_BRANCH"));
    // build identifiers are unconditional
    assertTrue(env.containsKey("BUILD_NUMBER"));
    assertTrue(env.containsKey("BUILD_ID"));
  }

  @Test
  void nullTriggerMetaIsTreatedAsAbsentNotAnError() {
    BuildRow b = build(99L, 2, null);
    Map<String, String> env = ImplicitBuildEnv.forBuild(b);
    assertFalse(env.containsKey("GIT_COMMIT"));
    assertFalse(env.containsKey("GIT_BRANCH"));
    assertEquals("2", env.get("BUILD_NUMBER"));
    assertEquals("99", env.get("BUILD_ID"));
  }

  @Test
  void blankTriggerMetaIsTreatedAsAbsent() {
    BuildRow b = build(99L, 2, "   ");
    Map<String, String> env = ImplicitBuildEnv.forBuild(b);
    assertFalse(env.containsKey("GIT_COMMIT"));
  }

  @Test
  void malformedTriggerMetaDoesNotBlowUpDispatch() {
    // Real-world failure mode: trigger_meta_json got corrupted (truncated, half-written, an
    // older receiver wrote a non-JSON literal). The dispatcher MUST NOT raise — that would
    // break every step in every build for an entirely cosmetic field.
    BuildRow b = build(5L, 5, "{not valid json");
    Map<String, String> env = ImplicitBuildEnv.forBuild(b);
    assertFalse(env.containsKey("GIT_COMMIT"));
    assertFalse(env.containsKey("GIT_BRANCH"));
    // identifiers still stamped
    assertEquals("5", env.get("BUILD_NUMBER"));
    assertEquals("5", env.get("BUILD_ID"));
  }

  @Test
  void ignoresUnknownFieldsInTriggerMeta() {
    BuildRow b =
        build(1L, 1, "{\"commitSha\":\"x\",\"actor\":\"kira.rai\",\"junk\":{\"nested\":true}}");
    Map<String, String> env = ImplicitBuildEnv.forBuild(b);
    assertEquals("x", env.get("GIT_COMMIT"));
    assertFalse(env.containsKey("actor"));
    assertFalse(env.containsKey("junk"));
  }

  private static BuildRow build(long id, int buildNumber, String triggerMetaJson) {
    BuildRow r = new BuildRow();
    r.id = id;
    r.buildNumber = buildNumber;
    r.triggerMetaJson = triggerMetaJson;
    return r;
  }
}
