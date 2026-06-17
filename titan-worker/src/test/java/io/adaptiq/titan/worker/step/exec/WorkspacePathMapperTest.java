package io.adaptiq.titan.worker.step.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WorkspacePathMapper} — the docker-out-of-docker bind-source translation
 * that closes the silent-empty-mount bug (#1246).
 *
 * <p>Adversarial-first (testing manifesto): the load-bearing case is NOT "translation works" but
 * "an UNCONFIGURED mapper is the identity" (so a bare-process / k8s worker is untouched) and "a
 * work dir that escapes the workspace root fails SAFE to identity rather than mis-translating a
 * path that lives outside the workspace onto the host root".
 */
final class WorkspacePathMapperTest {

  // ── Identity (translation OFF) — the zero-risk default ──────────────────────────────────────

  @Test
  void identity_returns_workDir_unchanged() {
    WorkspacePathMapper m = WorkspacePathMapper.identity();
    assertFalse(m.isActive());
    assertEquals(
        Path.of("/titan/worker/build-7").toString(),
        m.hostBindSource(Path.of("/titan/worker/build-7")));
  }

  @Test
  void of_blankHostRoot_isIdentity() {
    WorkspacePathMapper m = WorkspacePathMapper.of(Path.of("/titan/worker"), "");
    assertFalse(m.isActive());
    assertEquals(
        Path.of("/titan/worker/build-7").toString(),
        m.hostBindSource(Path.of("/titan/worker/build-7")));
  }

  @Test
  void of_nullHostRoot_isIdentity() {
    WorkspacePathMapper m = WorkspacePathMapper.of(Path.of("/titan/worker"), null);
    assertFalse(m.isActive());
  }

  @Test
  void of_nullWorkspaceRoot_isIdentity() {
    WorkspacePathMapper m = WorkspacePathMapper.of(null, "/var/lib/docker/volumes/x/_data");
    assertFalse(m.isActive());
    assertEquals(
        Path.of("/titan/worker/build-7").toString(),
        m.hostBindSource(Path.of("/titan/worker/build-7")));
  }

  // ── Active translation (the local docker-out-of-docker rig) ─────────────────────────────────

  @Test
  void active_translates_nestedWorkDir_to_hostRoot() {
    WorkspacePathMapper m =
        WorkspacePathMapper.of(
            Path.of("/titan/worker"), "/var/lib/docker/volumes/local_titan-ws/_data/worker");
    assertTrue(m.isActive());
    assertEquals(
        "/var/lib/docker/volumes/local_titan-ws/_data/worker/build-23",
        m.hostBindSource(Path.of("/titan/worker/build-23")));
  }

  @Test
  void active_translates_deeperPath() {
    WorkspacePathMapper m = WorkspacePathMapper.of(Path.of("/titan/worker"), "/host/ws");
    assertEquals(
        Path.of("/host/ws/build-23/frontend/dist").toString(),
        m.hostBindSource(Path.of("/titan/worker/build-23/frontend/dist")));
  }

  @Test
  void active_workDirEqualsRoot_mapsToHostRoot() {
    WorkspacePathMapper m = WorkspacePathMapper.of(Path.of("/titan/worker"), "/host/ws");
    assertEquals(Path.of("/host/ws").toString(), m.hostBindSource(Path.of("/titan/worker")));
  }

  // ── Adversarial: a work dir OUTSIDE the workspace must NOT be mis-translated ─────────────────

  @Test
  void active_workDirOutsideRoot_failsSafeToIdentity() {
    WorkspacePathMapper m = WorkspacePathMapper.of(Path.of("/titan/worker"), "/host/ws");
    // /etc/passwd is not under /titan/worker — mapping it under the host root would mount the
    // WRONG bytes. The mapper must return the absolute path unchanged, never "/host/ws/...".
    String out = m.hostBindSource(Path.of("/etc/passwd"));
    assertEquals(Path.of("/etc/passwd").toString(), out);
    assertFalse(out.startsWith("/host/ws"), "escaping path was mis-translated onto the host root");
  }

  @Test
  void active_siblingPrefixIsNotTreatedAsChild() {
    // /titan/worker-other shares a textual prefix with /titan/worker but is NOT a child of it.
    // Path.startsWith is component-wise, so this must fail safe to identity (not /host/ws/...).
    WorkspacePathMapper m = WorkspacePathMapper.of(Path.of("/titan/worker"), "/host/ws");
    String out = m.hostBindSource(Path.of("/titan/worker-other/build-1"));
    assertEquals(Path.of("/titan/worker-other/build-1").toString(), out);
    assertFalse(out.startsWith("/host/ws"), "sibling-prefix path was mis-translated as a child");
  }

  @Test
  void active_normalizesDotSegmentsBeforeTranslating() {
    WorkspacePathMapper m = WorkspacePathMapper.of(Path.of("/titan/worker"), "/host/ws");
    assertEquals(
        Path.of("/host/ws/build-9").toString(),
        m.hostBindSource(Path.of("/titan/worker/./sub/../build-9")));
  }
}
