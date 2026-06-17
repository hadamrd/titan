package io.adaptiq.titan.scm.pulsar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.scm.pulsar.PulsarEventSource.PulsarTrigger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for the {@link PulsarEventSource} clone + discovery leg (issue #1281).
 *
 * <p><strong>Fixture (recorded, no network).</strong> The test builds a real on-disk git
 * repository, writes a {@code .titan/pipelines/ci.yml}, commits, and publishes the commit under a
 * {@code refs/pulsar/changes/<id>} ref into a bare mirror repo that stands in for the Pulsar node's
 * git smart-HTTP endpoint. The event source is pointed at that mirror via a {@code file://} URL, so
 * the REAL {@code git init / fetch refs/pulsar/changes/<id> / checkout} path is exercised — just
 * against a local fixture instead of a live node.
 *
 * <p>Acceptance under test:
 *
 * <ul>
 *   <li>a change whose tip carries {@code .titan/pipelines/*.yml} → the source clones the change
 *       tip and discovery finds the pipeline;
 *   <li>a change whose tip has NO pipeline file → no build dispatched (honest no-op), not an error.
 * </ul>
 */
class PulsarCloneDiscoveryIT {

  private static final String REPO = "acme/web";

  @TempDir Path root;

  private Path mirror; // <root>/nodes/acme/web.git — the file:// remote
  private PulsarEventSource source;
  private String pipelineRevision; // change tip WITH a pipeline file
  private String barRevision; // change tip WITHOUT a pipeline file

  @BeforeEach
  void buildFixture() throws Exception {
    Path nodes = root.resolve("nodes");
    Path work = root.resolve("work");
    Files.createDirectories(work);

    // 1. A real repo with a pipeline file on the first commit.
    git(work, "init", "-q", "-b", "main", ".");
    write(work, ".titan/pipelines/ci.yml", "stages:\n  - name: build\n");
    write(work, "README.md", "acme web\n");
    git(work, "add", "-A");
    git(
        work,
        "-c",
        "user.email=t@titan",
        "-c",
        "user.name=titan",
        "commit",
        "-q",
        "-m",
        "with pipeline");
    pipelineRevision = revParse(work);

    // 2. A second commit that REMOVES the pipeline file — a change with no pipeline.
    git(work, "rm", "-q", ".titan/pipelines/ci.yml");
    git(
        work,
        "-c",
        "user.email=t@titan",
        "-c",
        "user.name=titan",
        "commit",
        "-q",
        "-m",
        "no pipeline");
    barRevision = revParse(work);

    // 3. Publish both tips under refs/pulsar/changes/<id> in the work repo, then mirror it so the
    //    custom refs are advertised over the file:// transport.
    git(work, "update-ref", "refs/pulsar/changes/42", pipelineRevision);
    git(work, "update-ref", "refs/pulsar/changes/43", barRevision);
    mirror = nodes.resolve(REPO + ".git");
    Files.createDirectories(mirror.getParent());
    git(root, "clone", "-q", "--mirror", work.toUri().toString(), mirror.toString());

    source = new PulsarEventSource(nodes.toUri().toString());
  }

  @Test
  void clonesChangeTip_andDiscoveryFindsPipeline() {
    PulsarChangeDiscovery change = PulsarChangeDiscovery.of(REPO, "42", pipelineRevision);

    Optional<PulsarTrigger> trigger = source.triggerFor(change);

    assertTrue(trigger.isPresent());
    // The trigger's revision is exactly the change-ref oid.
    assertEquals(pipelineRevision, trigger.get().request().revision());
    // The single clone materialized the pipeline file AND its bytes — no second clone needed.
    assertEquals(
        1, trigger.get().pipelines().size(), "expected exactly the .titan/pipelines/ci.yml");
    var pipeline = trigger.get().pipelines().get(0);
    assertTrue(pipeline.path().endsWith("ci.yml"));
    assertEquals(
        "stages:\n  - name: build\n", new String(pipeline.content(), StandardCharsets.UTF_8));
  }

  @Test
  void changeWithNoPipeline_isHonestNoOp() {
    PulsarChangeDiscovery change = PulsarChangeDiscovery.of(REPO, "43", barRevision);

    assertFalse(source.triggerFor(change).isPresent(), "no pipeline file → no build dispatched");
  }

  // ── git fixture helpers ─────────────────────────────────────────────────────

  private static String revParse(Path repo) throws Exception {
    return git(repo, "rev-parse", "HEAD").trim();
  }

  private static String git(Path cwd, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("git " + args[0] + " timed out");
    }
    if (p.exitValue() != 0) {
      throw new IllegalStateException("git " + args[0] + " exit=" + p.exitValue() + ": " + out);
    }
    return out;
  }

  private static void write(Path repo, String relPath, String content) {
    try {
      Path target = repo.resolve(relPath);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
