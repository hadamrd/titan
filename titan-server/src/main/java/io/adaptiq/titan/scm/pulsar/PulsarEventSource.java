package io.adaptiq.titan.scm.pulsar;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.discovery.DiscoverySource.DiscoveredFile;
import io.adaptiq.titan.discovery.LocalDirectorySource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a discovered Pulsar change into a real Titan build (issue #1281, convergence axis 2 /
 * scm-depth). Mirrors {@link io.adaptiq.titan.scm.github.GithubEventSource} — a provider adapter
 * that maps a scanned change to a normalized {@link PulsarTriggerRequest} — but, unlike the staged
 * GitHub source, carries the materialize+discover leg the convergence slice needs.
 *
 * <p>{@link #triggerFor(PulsarChangeDiscovery)} is the single hot-path entry point: it clones the
 * change revision from the node's git smart-HTTP endpoint ({@code <node>/<repo>.git}, fetch {@code
 * refs/pulsar/changes/<id>}) into a throwaway tree exactly once, discovers {@code
 * .titan/pipelines/*.yml} with the SAME {@link LocalDirectorySource} walker GitHub's discovery
 * reuses, and reads the pipeline bytes <em>while the tree still exists</em>. It returns a {@link
 * PulsarTrigger} carrying BOTH the {@link PulsarTriggerRequest} and the already-materialized {@link
 * PipelineFile}s, so the scheduled dispatch path never re-clones the change to read its pipelines.
 * A change with no pipeline file dispatches nothing ({@link Optional#empty()}), never an error.
 *
 * <p>No Pulsar specifics leak past this adapter: the planner / build core only ever sees a {@link
 * PulsarTriggerRequest} + the provider-neutral pipeline bytes, exactly as for GitHub.
 *
 * <p>The git clone routes through the overridable {@link #materialize} seam — the same {@code
 * ProcessBuilder} approach as {@link io.adaptiq.titan.discovery.ProcessGitHeadResolver} — so a unit
 * test can lay down a fixture tree with no network, while the integration test exercises the real
 * {@code git fetch} against a local {@code file://} bare-repo fixture.
 */
public class PulsarEventSource {

  private static final Logger LOGGER = Logger.getLogger(PulsarEventSource.class.getName());

  /**
   * The pipeline directory glob discovered in the materialized change tree — the same {@code
   * .titan/pipelines} convention GitHub discovery walks (mirrors {@code
   * GithubRepoScanner.PIPELINES_DIR}). Both {@code .yml} and {@code .yaml} match.
   */
  static final String PIPELINES_GLOB = ".titan/pipelines/*.{yml,yaml}";

  private static final long GIT_TIMEOUT_SECONDS = 60L;

  /** Base URL of the Pulsar node's git smart-HTTP endpoint (e.g. {@code https://node:8080}). */
  private final String nodeBaseUrl;

  public PulsarEventSource(@NonNull String nodeBaseUrl) {
    if (Objects.requireNonNull(nodeBaseUrl, "nodeBaseUrl").isBlank()) {
      throw new IllegalArgumentException("nodeBaseUrl must not be blank");
    }
    this.nodeBaseUrl = stripTrailingSlash(nodeBaseUrl);
  }

  /**
   * Map a scanned change to its Titan trigger request. The trigger's {@code revision} is exactly
   * the change-ref oid the scanner resolved and its {@code eventId} is deterministic — re-running
   * on the same change yields an identical request (acceptance: deterministic event identity).
   */
  @NonNull
  public PulsarTriggerRequest toTriggerRequest(@NonNull PulsarChangeDiscovery change) {
    return PulsarTriggerRequest.fromChange(change);
  }

  /** The git smart-HTTP clone URL for {@code repo} on this node: {@code <node>/<repo>.git}. */
  @NonNull
  public String gitRemoteUrl(@NonNull String repo) {
    Objects.requireNonNull(repo, "repo");
    return nodeBaseUrl + "/" + repo + ".git";
  }

  /**
   * Single hot-path entry point. Clone the change revision into a throwaway tree <em>once</em>,
   * discover its {@code .titan/pipelines/*.yml} files and read their bytes while the tree exists,
   * then return a {@link PulsarTrigger} carrying BOTH the request and the materialized pipelines —
   * so the dispatch path never clones the same change again. A change with no pipeline file
   * dispatches nothing — {@link Optional#empty()}, not an exception (acceptance #3). The temp tree
   * is always removed.
   *
   * @throws PulsarApiException if the clone/fetch itself fails (typed boundary error — Manifesto
   *     §"Errors": never a silent empty result for a transport failure)
   */
  @NonNull
  public Optional<PulsarTrigger> triggerFor(@NonNull PulsarChangeDiscovery change) {
    Objects.requireNonNull(change, "change");
    List<PipelineFile> pipelines = materializeAndDiscover(change);
    if (pipelines.isEmpty()) {
      LOGGER.log(
          Level.FINE,
          "[pulsar-trigger] change {0}@{1} has no .titan/pipelines/*.yml — no build dispatched",
          new Object[] {change.changeId(), change.repo()});
      return Optional.empty();
    }
    return Optional.of(new PulsarTrigger(toTriggerRequest(change), pipelines));
  }

  /**
   * Clone the change once, discover {@code .titan/pipelines/*.yml}, and read each match's bytes
   * before the throwaway tree is deleted. The returned {@link PipelineFile}s carry repo-relative
   * paths and the YAML content, so callers need neither the (deleted) tree nor a second clone.
   */
  @NonNull
  private List<PipelineFile> materializeAndDiscover(@NonNull PulsarChangeDiscovery change) {
    Path workdir;
    try {
      workdir = Files.createTempDirectory("titan-pulsar-clone-");
    } catch (IOException e) {
      throw new PulsarApiException(
          "could not create clone workdir for " + change.repo() + ": " + e.getMessage(), -1, e);
    }
    try {
      materialize(gitRemoteUrl(change.repo()), change.ref(), workdir);
      LocalDirectorySource source =
          new LocalDirectorySource("pulsar:" + change.repo(), workdir.toString());
      source.setPathPattern(PIPELINES_GLOB);
      List<PipelineFile> pipelines = new ArrayList<>();
      for (DiscoveredFile file : source.scan()) {
        String relPath = workdir.relativize(Path.of(file.getPath())).toString();
        pipelines.add(new PipelineFile(relPath, source.read(file.getPath())));
      }
      return List.copyOf(pipelines);
    } finally {
      deleteRecursively(workdir);
    }
  }

  /**
   * Materialize {@code ref} from {@code remoteUrl} into {@code workdir} via git smart-HTTP. Default
   * impl shells out to {@code git init && git fetch <url> <ref> && git checkout FETCH_HEAD}. The
   * fetch is a FULL (non-shallow) fetch — the Pulsar gitnode rejects shallow clone/fetch ({@code
   * fatal: remote error: shallow clone/fetch is not supported by Pulsar gitnode}). Overridable so a
   * unit test can lay down a fixture tree with no network.
   */
  protected void materialize(
      @NonNull String remoteUrl, @NonNull String ref, @NonNull Path workdir) {
    runGit(workdir, "init", "-q", ".");
    runGit(workdir, "fetch", "-q", remoteUrl, ref);
    runGit(workdir, "-c", "advice.detachedHead=false", "checkout", "-q", "FETCH_HEAD");
  }

  /**
   * Run one git subprocess in {@code cwd}; a non-zero exit surfaces a typed {@link
   * PulsarApiException}. Overridable so a unit test can capture the git argv without spawning a
   * subprocess.
   */
  protected void runGit(@NonNull Path cwd, @NonNull String... args) {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p;
    try {
      p = pb.start();
    } catch (IOException e) {
      throw new PulsarApiException("git " + args[0] + " could not start: " + e.getMessage(), -1, e);
    }
    String output;
    try (InputStream out = p.getInputStream()) {
      output = new String(out.readAllBytes(), StandardCharsets.UTF_8);
      if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new PulsarApiException("git " + args[0] + " timed out", -1);
      }
    } catch (IOException e) {
      throw new PulsarApiException("git " + args[0] + " failed: " + e.getMessage(), -1, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PulsarApiException("git " + args[0] + " interrupted", -1, e);
    }
    if (p.exitValue() != 0) {
      throw new PulsarApiException(
          "git " + args[0] + " exit=" + p.exitValue() + ": " + output.trim(), -1);
    }
  }

  /** Best-effort recursive delete of the throwaway clone tree. */
  private static void deleteRecursively(@NonNull Path dir) {
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  LOGGER.log(
                      Level.FINE,
                      "[pulsar-trigger] could not delete {0}: {1}",
                      new Object[] {p, e.getMessage()});
                }
              });
    } catch (IOException e) {
      LOGGER.log(
          Level.FINE,
          "[pulsar-trigger] could not clean clone tree {0}: {1}",
          new Object[] {dir, e.getMessage()});
    }
  }

  @NonNull
  private static String stripTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  /**
   * A dispatch-ready Pulsar trigger: the normalized {@link PulsarTriggerRequest} plus the pipeline
   * files captured from the SINGLE clone {@link #triggerFor} performed — so the scheduled dispatch
   * path reads the change's pipelines without cloning it a second time.
   */
  public record PulsarTrigger(
      @NonNull PulsarTriggerRequest request, @NonNull List<PipelineFile> pipelines) {
    public PulsarTrigger {
      Objects.requireNonNull(request, "request");
      pipelines = List.copyOf(pipelines);
    }
  }

  /**
   * One discovered {@code .titan/pipelines/*.yml}: its repo-relative path and its YAML bytes, read
   * while the throwaway clone tree still existed.
   */
  public record PipelineFile(@NonNull String path, @NonNull byte[] content) {
    public PipelineFile {
      Objects.requireNonNull(path, "path");
      content = content.clone();
    }

    /** Defensive copy — the bytes are owned by the caller, not shared with the record's field. */
    @Override
    @NonNull
    public byte[] content() {
      return content.clone();
    }
  }
}
