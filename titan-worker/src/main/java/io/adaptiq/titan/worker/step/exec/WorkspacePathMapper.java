package io.adaptiq.titan.worker.step.exec;

import java.nio.file.Path;

/**
 * Translates an in-worker workspace path to the equivalent path on the Docker <em>host</em>, so a
 * sibling container started through the shared daemon socket (docker-out-of-docker) bind-mounts the
 * real workspace bytes instead of an empty host directory.
 *
 * <p><strong>The silent-empty-mount bug (#1246).</strong> The worker runs as a container and asks
 * the HOST daemon (mounted {@code /var/run/docker.sock}) to start each {@code image:} stage. A bind
 * <em>source</em> is resolved by that daemon against the HOST filesystem, never the worker's own.
 * The worker's workspace lives on a named volume mounted at, e.g., {@code /titan/worker} INSIDE the
 * worker; that absolute path does not exist on the host, so the daemon silently creates an empty
 * directory there and mounts it — the stage container sees an <em>empty</em> {@code /workspace} and
 * every build that reads a checked-out file fails (maven: {@code pom.xml not found}; node: {@code
 * cd frontend} fails). Specs that use plain {@code sh} (no container) never hit it; the full-stack
 * spec — the only one with {@code image:} stages — surfaced it.
 *
 * <p><strong>The fix.</strong> When the operator declares the host path that backs the worker's
 * workspace root (env {@code TITAN_WORKSPACE_HOST_ROOT}), translate {@code <workspaceRoot>/<rel>} →
 * {@code <hostRoot>/<rel>} before binding. When unset — a bare-process worker, or a k8s pod where
 * the worker's path already IS the host path — the mapper is the {@linkplain #identity() identity}:
 * current behaviour, zero risk.
 *
 * <p>Immutable. {@code null} roots mean "not configured" → identity.
 */
public record WorkspacePathMapper(Path workspaceRoot, Path hostRoot) {

  /** A mapper that performs no translation — the bind source is the work dir as-is. */
  public static WorkspacePathMapper identity() {
    return new WorkspacePathMapper(null, null);
  }

  /**
   * Build a mapper, returning {@link #identity()} when either root is null/blank. Centralises the
   * "blank means off" rule so the boot wiring stays a one-liner.
   *
   * @param workspaceRoot the worker's in-container workspace root (e.g. {@code /titan/worker})
   * @param hostRoot the host path that backs {@code workspaceRoot} (e.g. {@code
   *     /var/lib/docker/volumes/local_titan-ws/_data/worker}); null/blank disables translation
   */
  public static WorkspacePathMapper of(Path workspaceRoot, String hostRoot) {
    if (workspaceRoot == null || hostRoot == null || hostRoot.isBlank()) {
      return identity();
    }
    return new WorkspacePathMapper(workspaceRoot, Path.of(hostRoot));
  }

  /** True when a host root is configured and translation is active. */
  public boolean isActive() {
    return workspaceRoot != null && hostRoot != null;
  }

  /**
   * The host path to bind as the stage container's {@code /workspace}. Returns the absolute work
   * dir unchanged unless a host root is configured AND {@code workDir} is genuinely under the
   * workspace root — an escaping path fails safe to identity rather than mis-translating outside
   * the workspace.
   *
   * @param workDir the worker-side absolute (or relative) path of the build workspace
   * @return the path string to hand the host daemon as the bind source
   */
  public String hostBindSource(Path workDir) {
    Path abs = workDir.toAbsolutePath().normalize();
    if (!isActive()) {
      return abs.toString();
    }
    Path root = workspaceRoot.toAbsolutePath().normalize();
    if (!abs.startsWith(root)) {
      // Should not happen — TaskExecutor always resolves workDir under workspaceRoot — but if it
      // ever does, mis-translating a path that lives OUTSIDE the workspace would be worse than not
      // translating at all. Fail safe to identity.
      return abs.toString();
    }
    Path rel = root.relativize(abs);
    return hostRoot.resolve(rel).toString();
  }
}
