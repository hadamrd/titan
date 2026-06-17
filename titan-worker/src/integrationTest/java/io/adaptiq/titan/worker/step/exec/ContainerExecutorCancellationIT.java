package io.adaptiq.titan.worker.step.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.adaptiq.titan.worker.step.LogSink;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies {@link ContainerExecutor} kills its running container when its cancel signal flips. */
class ContainerExecutorCancellationIT {

  @TempDir Path workDir;

  private static final LogSink SINK = (stream, text) -> {};

  /** True if a usable Docker CLI is on PATH — the container ITs assume this and skip otherwise. */
  private static boolean dockerAvailable() {
    try {
      Process p = new ProcessBuilder("docker", "version").redirectErrorStream(true).start();
      try (BufferedReader r =
          new BufferedReader(
              new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
        while (r.readLine() != null) {
          // drain
        }
      }
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** Run a command, returning its stdout; throws on a non-zero exit. */
  private static String sh(String... command) throws Exception {
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    StringBuilder out = new StringBuilder();
    try (BufferedReader r =
        new BufferedReader(
            new InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        out.append(line).append('\n');
      }
    }
    int exit = p.waitFor();
    if (exit != 0) {
      throw new IllegalStateException(command[0] + " exited " + exit + ": " + out);
    }
    return out.toString();
  }

  /** True if the Docker daemon can bind-mount the worker's workspace (daemon shares its FS). */
  private boolean bindMountWorks() {
    try {
      sh(
          "docker",
          "run",
          "--rm",
          "-v",
          workDir.toAbsolutePath() + ":/probe",
          "alpine:3.20",
          "true");
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void killsTheContainerPromptlyWhenCancelled() throws Exception {
    assumeTrue(dockerAvailable(), "Docker not available — skipping container cancellation IT");
    // ContainerExecutor bind-mounts the worker's workspace into the container. That needs the
    // Docker daemon and the worker to share a filesystem — true on a Titan agent host, but not
    // on a dev box whose daemon lives in an isolated VM/WSL. Probe, and skip if it cannot.
    assumeTrue(
        bindMountWorks(),
        "Docker daemon cannot bind-mount the worker workspace here — skipping (runs on the rig)");

    AtomicBoolean cancel = new AtomicBoolean(false);
    ContainerExecutor executor =
        new ContainerExecutor(
            "alpine:3.20", "test-agent", "task-token", "1", "node-1", cancel::get);

    // Use a sentinel of Integer.MIN_VALUE to detect "neither returned nor threw".
    AtomicInteger exitCode = new AtomicInteger(Integer.MIN_VALUE);
    Thread runner =
        new Thread(
            () -> {
              try {
                exitCode.set(
                    executor.run(
                        List.of("sh", "-c", "sleep 60"), workDir, Map.of(), SINK, "sleep 60"));
              } catch (Exception e) {
                // A killed container may cause the log-stream awaitCompletion() or waitContainer
                // to throw rather than return a non-zero code — that is still evidence of a kill.
                exitCode.set(-137);
              }
            });

    long start = System.nanoTime();
    runner.start();
    Thread.sleep(3_000L); // let the alpine image pull + the container start
    cancel.set(true);

    runner.join(30_000L);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertFalse(runner.isAlive(), "the executor thread must have returned");
    assertTrue(
        elapsedMs < 25_000,
        "the container must be killed shortly after the cancel, took " + elapsedMs + "ms");
    assertNotEquals(0, exitCode.get(), "a killed container must not report success");
  }
}
