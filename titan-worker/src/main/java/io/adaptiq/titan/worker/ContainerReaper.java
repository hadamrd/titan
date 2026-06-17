package io.adaptiq.titan.worker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.adaptiq.titan.worker.step.exec.DockerClients;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reaps orphaned Titan containers — the container analogue of design/26 Tier C's "wipe the
 * workspace on restart", and the design/30 reconcile model applied to containers (Chunk 6G; rebuilt
 * on the docker-java API in Chunk 32B).
 *
 * <p>{@code ContainerExecutor} force-removes its container in a {@code finally}, so the happy path
 * leaves nothing behind. A worker killed mid-step never reaches that cleanup: its container keeps
 * running, orphaned. The controller cannot reach a worker host's daemon — so the <em>worker</em>
 * owns its own containers. On startup a worker owns no in-flight container, so anything still
 * labelled {@code titan.worker=<thisAgent>} is, by definition, an orphan from a previous life and
 * is force-removed. The {@code titan.worker} label filter guarantees a worker only ever reaps
 * <em>its own</em> containers, never a peer's or a user's.
 *
 * <p>Best-effort: a worker with no Docker daemon need not even reach it — every failure here is
 * logged and swallowed; it must never block worker startup.
 */
final class ContainerReaper {

  private static final Logger LOG = LoggerFactory.getLogger(ContainerReaper.class);

  private final String agentId;

  ContainerReaper(String agentId) {
    this.agentId = agentId;
  }

  /**
   * Force-remove every container this worker owns ({@code titan.managed=true} + {@code
   * titan.worker=<agentId>}). Intended to run once at worker startup, before the poll loop claims
   * any task.
   *
   * @return the number of orphaned containers removed
   */
  int sweepOrphans() {
    List<Container> orphans;
    DockerClient docker;
    try {
      docker = DockerClients.shared();
      orphans =
          docker
              .listContainersCmd()
              .withShowAll(true)
              .withLabelFilter(Map.of("titan.managed", "true", "titan.worker", agentId))
              .exec();
    } catch (Exception e) {
      // No Docker daemon, or it is unreachable — fine for a worker with no image steps.
      LOG.debug("container orphan sweep skipped: {}", e.getMessage());
      return 0;
    }
    if (orphans.isEmpty()) {
      return 0;
    }
    LOG.info("reaping {} orphaned Titan container(s) from a previous worker life", orphans.size());
    int reaped = 0;
    for (Container orphan : orphans) {
      try {
        docker.removeContainerCmd(orphan.getId()).withForce(true).exec();
        reaped++;
      } catch (RuntimeException e) {
        LOG.warn("could not remove orphaned container {}: {}", orphan.getId(), e.getMessage());
      }
    }
    return reaped;
  }
}
