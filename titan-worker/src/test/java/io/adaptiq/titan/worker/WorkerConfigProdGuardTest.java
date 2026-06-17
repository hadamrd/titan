package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Audit #10 (v1-bar-5-escape-hatch-audit.md) / issue #936: in non-dev mode the worker must refuse
 * to boot when {@code TITAN_WORKSPACE} or {@code TITAN_LIBRARIES_ROOT} is missing — the {@code
 * ${java.io.tmpdir}} defaults are convenient on the local rig but unsafe in production (workspaces
 * vanish on host reboot, {@code /tmp} may be world-readable on shared rigs).
 *
 * <p>{@code TITAN_DEV_MODE=true} restores the tmpdir fallback for the local rig.
 */
final class WorkerConfigProdGuardTest {

  private static Map<String, String> baseProdEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("TITAN_DB_URL", "jdbc:postgresql://db:5432/titan");
    env.put("TITAN_DB_USER", "titan");
    env.put("TITAN_DB_PASSWORD", "titan");
    return env;
  }

  @Test
  void prodMode_missingWorkspace_throws() {
    Map<String, String> env = baseProdEnv();
    env.put("TITAN_LIBRARIES_ROOT", "/titan/libraries");
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> WorkerConfig.fromEnv(env));
    assertTrue(
        ex.getMessage().contains("TITAN_WORKSPACE"),
        "message must name the missing var: " + ex.getMessage());
  }

  @Test
  void prodMode_missingLibrariesRoot_throws() {
    Map<String, String> env = baseProdEnv();
    env.put("TITAN_WORKSPACE", "/titan/worker");
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> WorkerConfig.fromEnv(env));
    assertTrue(
        ex.getMessage().contains("TITAN_LIBRARIES_ROOT"),
        "message must name the missing var: " + ex.getMessage());
  }

  @Test
  void prodMode_blankWorkspace_throws() {
    // Blank is treated the same as missing — a stray TITAN_WORKSPACE="" from a poorly-
    // templated compose/Helm chart must NOT silently fall through to /tmp.
    Map<String, String> env = baseProdEnv();
    env.put("TITAN_WORKSPACE", "");
    env.put("TITAN_LIBRARIES_ROOT", "/titan/libraries");
    assertThrows(IllegalStateException.class, () -> WorkerConfig.fromEnv(env));
  }

  @Test
  void prodMode_bothSet_boots() {
    Map<String, String> env = baseProdEnv();
    env.put("TITAN_WORKSPACE", "/titan/worker");
    env.put("TITAN_LIBRARIES_ROOT", "/titan/libraries");
    WorkerConfig cfg = WorkerConfig.fromEnv(env);
    assertEquals("/titan/worker", cfg.workspaceRoot().toString());
    assertEquals("/titan/libraries", cfg.libraryCacheRoot().toString());
  }

  @Test
  void devMode_missingWorkspace_fallsBackToTmpdir() {
    // The local rig sets TITAN_DEV_MODE=true and intentionally omits TITAN_LIBRARIES_ROOT,
    // relying on the tmpdir default. That path must keep working.
    Map<String, String> env = baseProdEnv();
    env.put("TITAN_DEV_MODE", "true");
    WorkerConfig cfg = WorkerConfig.fromEnv(env);
    String tmp = System.getProperty("java.io.tmpdir");
    assertEquals(tmp + "/titan-workspace", cfg.workspaceRoot().toString());
    assertEquals(tmp + "/titan-libraries", cfg.libraryCacheRoot().toString());
  }

  @Test
  void devMode_explicitOverridesStillWin() {
    Map<String, String> env = baseProdEnv();
    env.put("TITAN_DEV_MODE", "true");
    env.put("TITAN_WORKSPACE", "/titan/worker");
    WorkerConfig cfg = WorkerConfig.fromEnv(env);
    assertEquals("/titan/worker", cfg.workspaceRoot().toString());
  }

  @Test
  void devModeFlag_caseInsensitive() {
    // Helm values often quote the literal string "True" — accept any casing of true rather
    // than fail-closed on a cosmetic difference.
    Map<String, String> env = baseProdEnv();
    env.put("TITAN_DEV_MODE", "TRUE");
    WorkerConfig cfg = WorkerConfig.fromEnv(env);
    assertTrue(cfg.workspaceRoot().toString().endsWith("/titan-workspace"));
  }

  @Test
  void devModeAnyOtherValue_isProd() {
    // Only the exact (case-insensitive) string "true" disables the guard. "yes", "1", "on"
    // must NOT — fail-closed defaults are the whole point of the audit.
    for (String s : new String[] {"false", "1", "yes", "on", ""}) {
      Map<String, String> env = baseProdEnv();
      env.put("TITAN_DEV_MODE", s);
      assertThrows(
          IllegalStateException.class,
          () -> WorkerConfig.fromEnv(env),
          "TITAN_DEV_MODE=" + s + " must NOT disable the prod guard");
    }
  }
}
