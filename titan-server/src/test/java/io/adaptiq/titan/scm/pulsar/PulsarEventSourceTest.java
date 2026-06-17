package io.adaptiq.titan.scm.pulsar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.scm.pulsar.PulsarEventSource.PulsarTrigger;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PulsarEventSource} — the change → trigger mapping and the clone+discovery
 * gate (issue #1281). The git clone leg is stubbed via the {@link PulsarEventSource#materialize}
 * seam so these stay network-free; the real {@code git fetch} is exercised by {@code
 * PulsarCloneDiscoveryIT}.
 *
 * <p>Acceptance under test:
 *
 * <ul>
 *   <li>a scanned change → a trigger whose {@code revision} == the change-ref oid; {@code eventId}
 *       is deterministic;
 *   <li>a change WITH a {@code .titan/pipelines/*.yml} → a dispatched trigger;
 *   <li>a change with NO pipeline file → no build dispatched ({@link Optional#empty()}), not an
 *       error;
 *   <li>a clone/fetch failure → a typed {@link PulsarApiException}, never a silent empty scan.
 * </ul>
 */
class PulsarEventSourceTest {

  private static final String OID = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";

  private static PulsarChangeDiscovery change(String repo, String changeId, String revision) {
    return PulsarChangeDiscovery.of(repo, changeId, revision);
  }

  // ── mapping: change → trigger ───────────────────────────────────────────────

  @Test
  void toTriggerRequest_carriesChangeRefOidAsRevision() {
    PulsarEventSource src = new PulsarEventSource("https://node:8080");

    PulsarTriggerRequest req = src.toTriggerRequest(change("acme/web", "42", OID));

    assertEquals(ScmProvider.PULSAR, req.provider());
    assertEquals("acme/web", req.repo());
    assertEquals("refs/pulsar/changes/42", req.ref());
    // The trigger's revision must be exactly the change-ref oid the scanner resolved.
    assertEquals(OID, req.revision());
    assertEquals("acme/web:42:" + OID, req.eventId());
  }

  @Test
  void toTriggerRequest_eventIdIsDeterministic() {
    PulsarEventSource src = new PulsarEventSource("https://node:8080");
    PulsarChangeDiscovery c = change("acme/web", "42", OID);

    assertEquals(src.toTriggerRequest(c).eventId(), src.toTriggerRequest(c).eventId());
  }

  @Test
  void gitRemoteUrl_appendsRepoDotGit_andTrimsTrailingSlash() {
    assertEquals(
        "https://node:8080/acme/web.git",
        new PulsarEventSource("https://node:8080/").gitRemoteUrl("acme/web"));
  }

  // ── discovery: single clone → trigger carries request + pipeline bytes ───────

  @Test
  void triggerFor_dispatchesWithPipelineBytes_fromSingleClone() {
    PulsarEventSource src =
        fakeMaterializer(dir -> writeFile(dir, ".titan/pipelines/ci.yml", "stages: []"));

    Optional<PulsarTrigger> trigger = src.triggerFor(change("acme/web", "42", OID));

    assertTrue(trigger.isPresent());
    // The request is the normalized trigger; revision is the change-ref oid.
    assertEquals(OID, trigger.get().request().revision());
    // The pipeline file + its bytes were materialized once and carried out — no re-clone needed.
    assertEquals(1, trigger.get().pipelines().size());
    var pipeline = trigger.get().pipelines().get(0);
    assertEquals(".titan/pipelines/ci.yml", pipeline.path());
    assertArrayEquals("stages: []".getBytes(StandardCharsets.UTF_8), pipeline.content());
  }

  @Test
  void triggerFor_matchesYamlExtensionToo() {
    PulsarEventSource src =
        fakeMaterializer(dir -> writeFile(dir, ".titan/pipelines/ci.yaml", "stages: []"));

    assertTrue(src.triggerFor(change("acme/web", "42", OID)).isPresent());
  }

  // ── adversarial / sad paths ─────────────────────────────────────────────────

  @Test
  void triggerFor_noPipelineFile_isHonestNoOp_notError() {
    // A materialized tree with no .titan/pipelines/*.yml — e.g. a plain change.
    PulsarEventSource src = fakeMaterializer(dir -> writeFile(dir, "README.md", "hi"));

    Optional<PulsarTrigger> trigger = src.triggerFor(change("acme/web", "42", OID));

    assertFalse(trigger.isPresent(), "no pipeline file → no build dispatched");
  }

  @Test
  void triggerFor_emptyTree_isHonestNoOp() {
    PulsarEventSource src = fakeMaterializer(dir -> {});

    assertFalse(src.triggerFor(change("acme/web", "42", OID)).isPresent());
  }

  @Test
  void triggerFor_cloneFailure_surfacesTypedError() {
    PulsarEventSource src =
        fakeMaterializer(
            dir -> {
              throw new PulsarApiException("git fetch exit=128: not found", -1);
            });

    assertThrows(PulsarApiException.class, () -> src.triggerFor(change("acme/web", "42", OID)));
  }

  @Test
  void constructor_blankNode_rejected() {
    assertThrows(IllegalArgumentException.class, () -> new PulsarEventSource("  "));
  }

  // ── regression guard: the fetch must be FULL, not shallow ────────────────────

  /**
   * Regression guard for the live blocker: the Pulsar gitnode rejects shallow clone/fetch ({@code
   * fatal: remote error: shallow clone/fetch is not supported by Pulsar gitnode}). The default
   * {@code materialize} must therefore issue a FULL fetch — its git argv must NOT carry {@code
   * --depth}. We capture the argv by overriding the {@code runGit} subprocess seam (no network).
   */
  @Test
  void materialize_issuesFullFetch_noDepthFlag() {
    List<List<String>> invocations = new ArrayList<>();
    PulsarEventSource src =
        new PulsarEventSource("https://node:8080") {
          @Override
          protected void runGit(Path cwd, String... args) {
            invocations.add(List.of(args));
          }
        };

    src.materialize("https://node:8080/acme/web.git", "refs/pulsar/changes/42", Path.of("/tmp/x"));

    List<String> fetch =
        invocations.stream()
            .filter(a -> !a.isEmpty() && a.get(0).equals("fetch"))
            .findFirst()
            .orElseThrow(
                () -> new AssertionError("expected a git fetch invocation: " + invocations));
    assertFalse(fetch.contains("--depth"), "fetch must be full (non-shallow): " + fetch);
    assertFalse(fetch.contains("1"), "fetch must not carry a depth value: " + fetch);
    // Still fetches the requested ref from the remote.
    assertTrue(
        fetch.contains("refs/pulsar/changes/42"), "fetch must target the change ref: " + fetch);
    assertTrue(
        fetch.contains("https://node:8080/acme/web.git"), "fetch must target the remote: " + fetch);
  }

  // ── test scaffolding ────────────────────────────────────────────────────────

  /**
   * A {@link PulsarEventSource} whose clone seam runs {@code effect} against the workdir instead.
   */
  private static PulsarEventSource fakeMaterializer(TreeWriter effect) {
    return new PulsarEventSource("https://node:8080") {
      @Override
      protected void materialize(String remoteUrl, String ref, Path workdir) {
        effect.write(workdir);
      }
    };
  }

  @FunctionalInterface
  private interface TreeWriter {
    void write(Path workdir);
  }

  private static void writeFile(Path workdir, String relPath, String content) {
    try {
      Path target = workdir.resolve(relPath);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
