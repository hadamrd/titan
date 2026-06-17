package io.adaptiq.titan.worker.step.exec;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Volume;
import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.StepExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Runs a command inside a container — the {@link StepExecutor} selected when a step declares an
 * {@code image} (Chunk 32B; design/31 §6G, design/32 §7).
 *
 * <p>Chunk 32B replaced the {@code docker run} CLI + output string-parsing with the typed
 * <strong>docker-java</strong> daemon API: create → start → stream logs → wait → remove. The worker
 * now needs only the daemon socket, not a CLI binary.
 *
 * <ul>
 *   <li><b>Labels</b> — every container is stamped {@code titan.managed} / {@code titan.worker} /
 *       {@code titan.task-token} / {@code build-id} / {@code node-id} so {@link
 *       io.adaptiq.titan.worker.ContainerReaper} can reap a crash-orphan and tell it from a user's
 *       container (design/30).
 *   <li><b>Workspace</b> — the host workspace is bind-mounted at a fixed {@code /workspace} and set
 *       as the working directory; portable across host OSes.
 *   <li><b>Environment</b> — only the step environment is passed in. The worker's own environment,
 *       its {@code TITAN_*} credentials especially, never crosses (design/26 Tier C).
 *   <li><b>Cleanup</b> — the container is force-removed in a {@code finally}; a worker crash
 *       between start and removal leaves a labelled orphan the reaper collects.
 * </ul>
 */
public final class ContainerExecutor implements StepExecutor {

  private final String image;
  private final String agentId;
  private final String taskToken;
  private final String buildId;
  private final String nodeId;
  private final BooleanSupplier cancelled;
  private final WorkspacePathMapper workspaceMapper;

  /** Distinguishes the cancel-watcher threads of concurrent container steps in a thread dump. */
  private static final java.util.concurrent.atomic.AtomicInteger WATCHER_SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  /**
   * Construct with no workspace path translation — the bind source is the work dir as-is. Correct
   * for a bare-process worker, or a k8s pod where the worker's workspace path already IS the host
   * path. Used by Tier-1 manifest container steps and the cancellation IT.
   */
  public ContainerExecutor(
      String image,
      String agentId,
      String taskToken,
      String buildId,
      String nodeId,
      BooleanSupplier cancelled) {
    this(image, agentId, taskToken, buildId, nodeId, cancelled, WorkspacePathMapper.identity());
  }

  /**
   * Construct with an explicit {@link WorkspacePathMapper}. The pipeline {@code image:} path
   * (TaskExecutor) passes the boot-configured mapper so docker-out-of-docker bind mounts resolve to
   * the real host workspace bytes rather than an empty host dir (#1246).
   */
  public ContainerExecutor(
      String image,
      String agentId,
      String taskToken,
      String buildId,
      String nodeId,
      BooleanSupplier cancelled,
      WorkspacePathMapper workspaceMapper) {
    this.image = image;
    this.agentId = agentId;
    this.taskToken = taskToken;
    this.buildId = buildId;
    this.nodeId = nodeId;
    this.cancelled = cancelled;
    this.workspaceMapper = workspaceMapper;
  }

  @Override
  public int run(
      List<String> command,
      Path workDir,
      Map<String, String> env,
      LogSink log,
      String displayCommand)
      throws Exception {
    DockerClient docker = DockerClients.shared();
    ensureImagePresent(docker, log);

    List<String> envList = new ArrayList<>();
    for (Map.Entry<String, String> e : env.entrySet()) {
      envList.add(e.getKey() + "=" + e.getValue());
    }
    // Translate the worker-side workspace path to the host path for the bind SOURCE. The host
    // daemon resolves the source against the HOST filesystem, not the worker container's — without
    // this, a docker-out-of-docker rig mounts an empty host dir and the stage sees an empty
    // /workspace (#1246). Identity mapper (no host root configured) leaves the path unchanged.
    String bindSource = workspaceMapper.hostBindSource(workDir);
    HostConfig hostConfig =
        HostConfig.newHostConfig().withBinds(new Bind(bindSource, new Volume("/workspace")));

    CreateContainerResponse created =
        docker
            .createContainerCmd(image)
            .withHostConfig(hostConfig)
            .withWorkingDir("/workspace")
            .withEnv(envList)
            .withLabels(labels())
            .withCmd(command)
            .exec();
    String id = created.getId();
    log.system(
        "$ ["
            + image
            + "] "
            + (displayCommand != null ? displayCommand : String.join(" ", command)));
    docker.startContainerCmd(id).exec();

    AtomicBoolean done = new AtomicBoolean(false);
    Thread cancelWatcher =
        new Thread(
            () -> {
              while (!done.get()) {
                try {
                  Thread.sleep(2000L);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                if (done.get()) {
                  return;
                }
                boolean isCancelled;
                try {
                  isCancelled = cancelled.getAsBoolean();
                } catch (RuntimeException e) {
                  // a transient supplier (DB) failure must not kill a healthy step
                  continue;
                }
                if (isCancelled) {
                  try {
                    docker.killContainerCmd(id).exec();
                  } catch (RuntimeException ignored) {
                    // container already gone — the await below will return
                  }
                  return;
                }
              }
            },
            "titan-container-cancel-" + WATCHER_SEQ.incrementAndGet());
    cancelWatcher.setDaemon(true);
    cancelWatcher.start();

    int statusCode;
    try {
      docker
          .logContainerCmd(id)
          .withStdOut(true)
          .withStdErr(true)
          .withFollowStream(true)
          .withTailAll()
          .exec(new LogStreamCallback(log))
          .awaitCompletion();
      statusCode =
          docker.waitContainerCmd(id).exec(new WaitContainerResultCallback()).awaitStatusCode();
    } finally {
      done.set(true);
      cancelWatcher.interrupt();
      try {
        docker.removeContainerCmd(id).withForce(true).exec();
      } catch (RuntimeException e) {
        log.system("warning: could not remove container " + id + ": " + e.getMessage());
      }
    }
    return statusCode;
  }

  /** Pull the image if the daemon does not already have it. */
  private void ensureImagePresent(DockerClient docker, LogSink log) throws Exception {
    try {
      docker.inspectImageCmd(image).exec();
    } catch (NotFoundException absent) {
      log.system("pulling image " + image);
      docker.pullImageCmd(image).exec(new PullImageResultCallback()).awaitCompletion();
    }
  }

  /** The attribution labels — what lets {@code ContainerReaper} reap only this worker's orphans. */
  private Map<String, String> labels() {
    Map<String, String> labels = new LinkedHashMap<>();
    labels.put("titan.managed", "true");
    labels.put("titan.worker", agentId);
    labels.put("titan.task-token", taskToken);
    if (buildId != null && !buildId.isBlank()) {
      labels.put("titan.build-id", buildId);
    }
    if (nodeId != null && !nodeId.isBlank()) {
      labels.put("titan.node-id", nodeId);
    }
    return labels;
  }

  /** Streams container stdout/stderr frames into the step's {@link LogSink}, line by line. */
  private static final class LogStreamCallback extends ResultCallback.Adapter<Frame> {

    private final LogSink log;

    LogStreamCallback(LogSink log) {
      this.log = log;
    }

    @Override
    public void onNext(Frame frame) {
      String stream = frame.getStreamType() == StreamType.STDERR ? "stderr" : "stdout";
      String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
      for (String line : text.split("\\R", -1)) {
        if (!line.isEmpty()) {
          log.line(stream, line);
        }
      }
    }
  }
}
