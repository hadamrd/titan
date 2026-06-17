package io.adaptiq.titan.worker;

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Worker configuration, sourced entirely from environment variables so the worker can run unchanged
 * as a bare process, a systemd unit, or a container.
 *
 * @param artifactStoreKind the {@code ArtifactStore} backend to use ({@code TITAN_ARTIFACT_STORE} —
 *     {@code "fs"}, {@code "s3"}, …), or blank when no store is configured (design/41 §8.6)
 * @param artifactStoreConfig backend config — every {@code TITAN_ARTIFACT_*} variable other than
 *     {@code TITAN_ARTIFACT_STORE}, prefix-stripped and lower-cased ({@code TITAN_ARTIFACT_ROOT} →
 *     {@code root}). Generic by design: a new backend's keys flow through with no code change.
 *     <ul>
 *       <li>the {@code fs} backend reads {@code TITAN_ARTIFACT_ROOT} → {@code root};
 *       <li>the {@code s3} backend (design/46) reads {@code TITAN_ARTIFACT_BUCKET} → {@code
 *           bucket}, {@code TITAN_ARTIFACT_ENDPOINT} → {@code endpoint} (set for R2, unset for AWS
 *           S3), {@code TITAN_ARTIFACT_REGION} → {@code region} (R2: {@code auto}), {@code
 *           TITAN_ARTIFACT_ACCESSKEY} → {@code accesskey}, {@code TITAN_ARTIFACT_SECRETKEY} →
 *           {@code secretkey}, {@code TITAN_ARTIFACT_PATHSTYLE} → {@code pathstyle}. The
 *           access/secret keys come from Infisical, mounted as env — never baked into the image.
 *       <li>the {@code nexus} backend (design/49) reads {@code TITAN_ARTIFACT_URL} → {@code url}
 *           (the Nexus base URL), {@code TITAN_ARTIFACT_REPOSITORY} → {@code repository} (the raw
 *           hosted repo name), {@code TITAN_ARTIFACT_USERNAME} → {@code username} and {@code
 *           TITAN_ARTIFACT_PASSWORD} → {@code password} (the Nexus user/password, from Infisical —
 *           never baked into the image).
 *     </ul>
 *     The prefix-strip/lower-case mapping below already produces these exact keys, so the {@code
 *     s3} and {@code nexus} backends need no worker code change.
 */
record WorkerConfig(
    String jdbcUrl,
    String dbUser,
    String dbPassword,
    String agentId,
    String displayName,
    String labels,
    int numExecutors,
    String remoteFs,
    String usageMode,
    String queueName,
    String synthesisQueue,
    Path workspaceRoot,
    String workspaceHostRoot,
    Path libraryCacheRoot,
    long pollIntervalMs,
    long heartbeatIntervalMs,
    String artifactStoreKind,
    Map<String, String> artifactStoreConfig) {

  /**
   * The well-known shared queue every worker polls for {@code SYNTHESIZE} tasks (design/38 Stage
   * 1b). Synthesis is not agent-pinned — any worker may run it — so it sits on a shared queue, not
   * an agent-id queue. Must match {@code QueueProcessor.SYNTHESIS_QUEUE} on the controller.
   */
  static final String DEFAULT_SYNTHESIS_QUEUE = "synthesis";

  /**
   * The list of step queues this worker drains, derived from labels + explicit overrides + the
   * implicit {@code default} fallback (#824).
   *
   * <p>{@code StepDispatcher#dispatchStepTask} resolves a step's target queue to the stage's {@code
   * agent:} label (e.g. {@code linux}), or the pipeline-level default, or {@code default}. Before
   * #824 the worker only polled the single {@code TITAN_QUEUE} (default: {@code default}) so
   * pipelines pinned to {@code agent: linux} sat in {@code QUEUED} forever — no worker subscribed
   * to that queue. The worker now polls one queue per declared label, plus the explicit {@code
   * TITAN_QUEUE}, plus {@code default} as the universal safety net (so a worker with {@code
   * TITAN_LABELS=linux} still serves stages with no {@code agent:} at all).
   *
   * <p>Order matters: this is the per-tick scan order in {@link TitanWorker#dispatchOne}. The
   * {@code synthesis} queue is drained separately and ALWAYS last (it is the shared low-priority
   * pool — never starve step work for synthesis).
   *
   * <p>Dedup is order-preserving: the first occurrence wins, so the explicit {@code TITAN_QUEUE}
   * shows up at the front when set and {@code default} never appears twice.
   */
  List<String> queueNames() {
    LinkedHashSet<String> queues = new LinkedHashSet<>();
    // The explicit step-queue override comes first — operators can pin priority by setting it.
    if (queueName != null && !queueName.isBlank()) {
      queues.add(queueName);
    }
    // Then one queue per declared label — the fix for #824. CSV, trimmed, blank tokens dropped.
    if (labels != null && !labels.isBlank()) {
      for (String token : labels.split(",")) {
        String t = token.trim();
        if (!t.isEmpty()) {
          queues.add(t);
        }
      }
    }
    // The agent's own id is always a valid queue target (per-agent-pinned tasks, future).
    if (agentId != null && !agentId.isBlank()) {
      queues.add(agentId);
    }
    // Universal fallback: every worker drains "default" so a stage with no agent: still runs.
    queues.add("default");
    return new ArrayList<>(queues);
  }

  static WorkerConfig fromEnv() {
    return fromEnv(System.getenv());
  }

  /**
   * Test-seam overload: read everything from the given {@code env} map instead of {@link
   * System#getenv()}. The no-arg {@link #fromEnv()} delegates here with the live process
   * environment.
   */
  static WorkerConfig fromEnv(Map<String, String> env) {
    String host = hostname();
    boolean devMode = "true".equalsIgnoreCase(env(env, "TITAN_DEV_MODE", "false"));
    // Audit #10 (v1-bar-5-escape-hatch-audit.md, follow-up #936): tmpdir
    // workspace/libraries defaults are convenient on the local rig but unsafe
    // in production — workspaces vanish on host reboot and /tmp may be
    // world-readable on shared rigs. In non-dev mode the worker must be told
    // explicitly where to put them.
    String workspace = env.get("TITAN_WORKSPACE");
    String librariesRoot = env.get("TITAN_LIBRARIES_ROOT");
    if (!devMode) {
      if (workspace == null || workspace.isBlank()) {
        throw new IllegalStateException(
            "TITAN_WORKSPACE must be set explicitly when TITAN_DEV_MODE is not true. "
                + "Workspaces in ${java.io.tmpdir} vanish on host reboot and may be "
                + "world-readable on shared rigs. Set TITAN_WORKSPACE to a persistent path "
                + "(e.g. /titan/worker), or set TITAN_DEV_MODE=true for the local rig.");
      }
      if (librariesRoot == null || librariesRoot.isBlank()) {
        throw new IllegalStateException(
            "TITAN_LIBRARIES_ROOT must be set explicitly when TITAN_DEV_MODE is not true. "
                + "Set TITAN_LIBRARIES_ROOT to a persistent path (e.g. /titan/libraries), "
                + "or set TITAN_DEV_MODE=true for the local rig.");
      }
    }
    String workspacePath =
        (workspace == null || workspace.isBlank())
            ? System.getProperty("java.io.tmpdir") + "/titan-workspace"
            : workspace;
    String librariesPath =
        (librariesRoot == null || librariesRoot.isBlank())
            ? System.getProperty("java.io.tmpdir") + "/titan-libraries"
            : librariesRoot;
    return new WorkerConfig(
        env(env, "TITAN_DB_URL", "jdbc:postgresql://localhost:5432/titan"),
        env(env, "TITAN_DB_USER", "titan"),
        env(env, "TITAN_DB_PASSWORD", "titan"),
        env(env, "TITAN_AGENT_ID", "worker-" + host),
        env(env, "TITAN_AGENT_NAME", "Titan Worker @ " + host),
        env(env, "TITAN_LABELS", "linux"),
        Integer.parseInt(env(env, "TITAN_EXECUTORS", "1")),
        // The node's workspace root + usage mode — reported into
        // titan.agents, where the controller mints the TitanNode from
        // them (design/28). remoteFs must be storage the controller and
        // worker share at the same path.
        env(env, "TITAN_REMOTE_FS", "/titan"),
        env(env, "TITAN_MODE", "NORMAL"),
        env(env, "TITAN_QUEUE", "default"),
        env(env, "TITAN_SYNTHESIS_QUEUE", DEFAULT_SYNTHESIS_QUEUE),
        Paths.get(workspacePath),
        // Docker-out-of-docker bind-mount translation (#1246). When the worker runs as a container
        // and starts `image:` stages through the shared host daemon socket, the bind SOURCE is
        // resolved on the HOST — so the host path that backs TITAN_WORKSPACE must be declared here
        // or the stage gets an empty /workspace. Blank on a bare-process / k8s-hostPath worker
        // (path already == host path) → WorkspacePathMapper.identity(), no translation.
        env(env, "TITAN_WORKSPACE_HOST_ROOT", ""),
        // Root of the resolved shared libraries — each declared library is a
        // <root>/<name>/vars/*.groovy tree, loaded before a `script` step runs
        // (design/29 §10, Chunk 6F).
        Paths.get(librariesPath),
        Long.parseLong(env(env, "TITAN_POLL_MS", "1000")),
        Long.parseLong(env(env, "TITAN_HEARTBEAT_MS", "10000")),
        // The artifact store: a kind plus its backend-specific config (design/41 §8.6).
        // Blank kind → no store; archiveArtifacts then fails closed rather than at boot.
        env(env, "TITAN_ARTIFACT_STORE", ""),
        artifactConfig(env));
  }

  private static String env(Map<String, String> env, String key, String fallback) {
    String v = env.get(key);
    return (v == null || v.isBlank()) ? fallback : v;
  }

  /**
   * Collect the {@code ArtifactStore} backend config from the environment: every {@code
   * TITAN_ARTIFACT_*} variable except {@code TITAN_ARTIFACT_STORE}, with the {@code
   * TITAN_ARTIFACT_} prefix stripped and the key lower-cased — so {@code TITAN_ARTIFACT_ROOT}
   * becomes {@code root}. Generic on purpose: a new backend (an S3 bucket, a Nexus URL) needs no
   * change here, only its own {@code TITAN_ARTIFACT_*} variables.
   */
  private static Map<String, String> artifactConfig(Map<String, String> env) {
    Map<String, String> cfg = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : env.entrySet()) {
      String key = e.getKey();
      if (key.startsWith("TITAN_ARTIFACT_") && !key.equals("TITAN_ARTIFACT_STORE")) {
        cfg.put(key.substring("TITAN_ARTIFACT_".length()).toLowerCase(Locale.ROOT), e.getValue());
      }
    }
    return Map.copyOf(cfg);
  }

  private static String hostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      return "unknown";
    }
  }
}
