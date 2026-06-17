package io.adaptiq.titan.worker.step.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.LogSink;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies {@link LocalProcessExecutor} kills its subprocess when its cancel signal flips. */
class LocalProcessExecutorCancelTest {

  @TempDir Path workDir;

  /** A LogSink that discards — these tests assert on timing/exit, not log content. */
  private static final LogSink SINK = (stream, text) -> {};

  @Test
  void killsTheProcessPromptlyWhenCancelled() throws Exception {
    AtomicBoolean cancel = new AtomicBoolean(false);
    LocalProcessExecutor exec = new LocalProcessExecutor(cancel::get);

    Thread flipper =
        new Thread(
            () -> {
              try {
                Thread.sleep(500);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              cancel.set(true);
            });

    long start = System.nanoTime();
    flipper.start();
    int exitCode = exec.run(List.of("sh", "-c", "sleep 60"), workDir, Map.of(), SINK, "sleep 60");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    flipper.join();

    assertTrue(
        elapsedMs < 15_000,
        "a cancelled `sleep 60` must be killed quickly, took " + elapsedMs + "ms");
    assertNotEquals(0, exitCode, "a killed process must not report success");
  }

  @Test
  void anUncancelledProcessRunsToCompletionAndReportsItsExitCode() throws Exception {
    LocalProcessExecutor exec = new LocalProcessExecutor(() -> false);
    int exitCode = exec.run(List.of("sh", "-c", "exit 0"), workDir, Map.of(), SINK, "exit 0");
    assertEquals(0, exitCode, "an uncancelled successful process returns 0");
  }

  @Test
  void theNoArgConstructorNeverCancels() throws Exception {
    LocalProcessExecutor exec = new LocalProcessExecutor();
    int exitCode = exec.run(List.of("sh", "-c", "exit 7"), workDir, Map.of(), SINK, "exit 7");
    assertEquals(7, exitCode, "exit code is propagated unchanged");
  }

  /**
   * SIGTERM-honouring step (#668): a process that traps SIGTERM and exits cleanly is killed by the
   * soft signal, not by SIGKILL. We assert this by recording log lines — the executor logs "exited
   * cleanly after SIGTERM" on a graceful exit and "escalating to SIGKILL" only when the grace
   * window elapsed. The shell trap below exits via {@code exit 0} on SIGTERM so the grace-period
   * waitFor() succeeds immediately and SIGKILL is never reached.
   */
  @Test
  void sendsSigtermBeforeSigkillSoGracefulProcessesCanExit() throws Exception {
    CopyOnWriteArrayList<String> systemLog = new CopyOnWriteArrayList<>();
    LogSink sink =
        new LogSink() {
          @Override
          public void line(String stream, String text) {}

          @Override
          public void system(String text) {
            systemLog.add(text);
          }
        };

    AtomicBoolean cancel = new AtomicBoolean(false);
    LocalProcessExecutor exec = new LocalProcessExecutor(cancel::get);

    Thread flipper =
        new Thread(
            () -> {
              try {
                Thread.sleep(500);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              cancel.set(true);
            });

    long start = System.nanoTime();
    flipper.start();
    // trap SIGTERM, ack it, exit 0. The executor must observe the clean exit during the grace
    // window and never escalate to SIGKILL.
    int exitCode =
        exec.run(
            List.of("sh", "-c", "trap 'exit 0' TERM; while :; do sleep 1; done"),
            workDir,
            Map.of(),
            sink,
            "trap-and-exit");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    flipper.join();

    assertNotEquals(0, exitCode, "the executor reports a killed exit even on graceful SIGTERM");
    assertTrue(elapsedMs < 8_000, "graceful exit must not pay the SIGTERM grace timeout");
    assertTrue(
        systemLog.stream().anyMatch(s -> s.contains("SIGTERM")),
        "SIGTERM was sent; log was: " + systemLog);
    assertTrue(
        systemLog.stream().noneMatch(s -> s.contains("SIGKILL")),
        "a SIGTERM-honouring process must NOT be escalated to SIGKILL; log was: " + systemLog);
  }

  /**
   * SIGTERM-ignoring step (#668): a process that traps and SWALLOWS SIGTERM is escalated to SIGKILL
   * after the grace period. We assert the kill happens (process exits non-zero) and the log records
   * the escalation. We do NOT assert the full 10s wait — the assertion is on the signal-escalation
   * sequence, not on timing.
   */
  @Test
  void escalatesToSigkillWhenSigtermIsIgnored() throws Exception {
    CopyOnWriteArrayList<String> systemLog = new CopyOnWriteArrayList<>();
    LogSink sink =
        new LogSink() {
          @Override
          public void line(String stream, String text) {}

          @Override
          public void system(String text) {
            systemLog.add(text);
          }
        };

    AtomicBoolean cancel = new AtomicBoolean(false);
    LocalProcessExecutor exec = new LocalProcessExecutor(cancel::get);

    Thread flipper =
        new Thread(
            () -> {
              try {
                Thread.sleep(500);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              cancel.set(true);
            });

    flipper.start();
    // trap SIGTERM and ignore it. Only SIGKILL can stop this.
    int exitCode =
        exec.run(
            List.of("sh", "-c", "trap '' TERM; while :; do sleep 1; done"),
            workDir,
            Map.of(),
            sink,
            "trap-and-ignore");
    flipper.join();

    assertNotEquals(0, exitCode, "a SIGKILLed process must not report success");
    assertTrue(
        systemLog.stream().anyMatch(s -> s.contains("SIGTERM")),
        "SIGTERM must have been sent first; log was: " + systemLog);
    assertTrue(
        systemLog.stream().anyMatch(s -> s.contains("SIGKILL")),
        "SIGTERM-ignoring process must be escalated to SIGKILL; log was: " + systemLog);
  }
}
