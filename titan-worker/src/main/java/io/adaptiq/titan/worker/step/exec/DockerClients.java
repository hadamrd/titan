package io.adaptiq.titan.worker.step.exec;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;

/**
 * Builds and holds the worker's one {@link DockerClient} (Chunk 32B).
 *
 * <p>One client per worker process — it owns an HTTP connection pool to the daemon, so a per-task
 * client would be wasteful. It is created lazily on first use: a worker that never runs a
 * containerized step never touches Docker, and a worker with no Docker daemon still starts fine
 * (building the client reads config only — it does not connect; connection happens on the first
 * command and is handled where that command runs).
 *
 * <p>The daemon address comes from the standard {@code DOCKER_HOST} discovery — so this works
 * against Docker or a Docker-API-compatible Podman socket without a CLI binary on PATH.
 */
public final class DockerClients {

  private static volatile DockerClient shared;

  private DockerClients() {}

  /** The shared, lazily-created {@link DockerClient} for this worker process. */
  public static DockerClient shared() {
    DockerClient local = shared;
    if (local == null) {
      synchronized (DockerClients.class) {
        if (shared == null) {
          DockerClientConfig config =
              DefaultDockerClientConfig.createDefaultConfigBuilder().build();
          DockerHttpClient http =
              new ApacheDockerHttpClient.Builder()
                  .dockerHost(config.getDockerHost())
                  .sslConfig(config.getSSLConfig())
                  .maxConnections(20)
                  .build();
          shared = DockerClientImpl.getInstance(config, http);
        }
        local = shared;
      }
    }
    return local;
  }
}
