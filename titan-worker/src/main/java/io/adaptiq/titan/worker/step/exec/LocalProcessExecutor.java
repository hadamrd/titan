package io.adaptiq.titan.worker.step.exec;

import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.StepExecutor;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Runs a command as an ordinary local process on the worker host — the default {@link StepExecutor}
 * (Chunk 32A; design/31 §6G).
 *
 * <p>Environment (design/26 Tier C): the process inherits the worker host's environment so {@code
 * PATH}/{@code HOME} work, but the worker's own {@code TITAN_*} variables — the DB credentials —
 * are stripped before the step's own {@code env} is layered on. A {@code TITAN_*} variable the step
 * itself declares ({@code TITAN_SSH_KEY_*}, design/41) is kept — the strip skips any key the step's
 * {@code env} also defines.
 *
 * <p>32A keeps the {@link ProcessBuilder} mechanism; Chunk 32B rebuilds the internals on {@code
 * zt-exec} behind this same interface.
 *
 * <p>Cancellation-aware: when constructed with a {@link BooleanSupplier} (the worker binds it to
 * {@code () -> WorkerDb.isCancelled(taskId)}), {@code run} polls it while the process runs and
 * {@code destroyForcibly()}s the process when it flips — turning a {@code task_queue} CANCELLED
 * (set by a timeout or a build abort) into a real process kill. The no-arg constructor never
 * cancels, preserving the original behaviour for the step TCK and any non-worker call site.
 */
public final class LocalProcessExecutor implements StepExecutor {

  /** How often the run loop checks the cancel signal. */
  private static final long POLL_SECONDS = 2L;

  /**
   * Grace period after SIGTERM (#668). The process gets a chance to flush stdout, write a final log
   * line, and exit cleanly via its own SIGTERM handler before SIGKILL is sent. Ten seconds is the
   * Kubernetes default {@code terminationGracePeriodSeconds} — workers should match that intuition.
   */
  private static final long SIGTERM_GRACE_SECONDS = 10L;

  /** Synthetic non-zero exit code reported for a process this executor killed. */
  private static final int KILLED_EXIT_CODE = 143;

  /** Distinguishes the log-pump threads of concurrent executions in a thread dump. */
  private static final java.util.concurrent.atomic.AtomicInteger SEQ =
      new java.util.concurrent.atomic.AtomicInteger();

  private final BooleanSupplier cancelled;

  /** A non-cancellable executor — original behaviour. */
  public LocalProcessExecutor() {
    this(() -> false);
  }

  /**
   * A cancellable executor — {@code cancelled} is polled (on the thread that calls {@code run})
   * every {@value #POLL_SECONDS}s while the process runs; it must be safe to call repeatedly.
   */
  public LocalProcessExecutor(BooleanSupplier cancelled) {
    this.cancelled = cancelled;
  }

  @Override
  public int run(
      List<String> command,
      Path workDir,
      Map<String, String> env,
      LogSink log,
      String displayCommand)
      throws Exception {
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(workDir.toFile());
    pb.redirectErrorStream(false);
    Map<String, String> procEnv = pb.environment();
    // Strip the worker's own inherited TITAN_* vars (DB credentials etc.) — but never one the
    // step itself declares: that is step-authoritative env, not worker ambient. Skipping
    // env-defined keys makes the strip safe even if the putAll below were ever reordered.
    procEnv.keySet().removeIf(k -> k.startsWith("TITAN_") && !env.containsKey(k));
    procEnv.putAll(env);

    log.system("$ " + (displayCommand != null ? displayCommand : String.join(" ", command)));
    Process proc = pb.start();
    int seq = SEQ.incrementAndGet();
    Thread stdoutPump =
        new Thread(() -> pump(proc.getInputStream(), log, "stdout"), "log-stdout-" + seq);
    Thread stderrPump =
        new Thread(() -> pump(proc.getErrorStream(), log, "stderr"), "log-stderr-" + seq);
    stdoutPump.start();
    stderrPump.start();

    boolean killed = false;
    try {
      while (!proc.waitFor(POLL_SECONDS, TimeUnit.SECONDS)) {
        if (cancelRequested(log)) {
          terminateGracefully(proc, log);
          killed = true;
          break;
        }
      }
    } finally {
      stdoutPump.join();
      stderrPump.join();
    }
    return killed ? KILLED_EXIT_CODE : proc.exitValue();
  }

  /**
   * Escalating shutdown (#668): SIGTERM (JDK {@code Process.destroy}) first, wait {@value
   * #SIGTERM_GRACE_SECONDS}s for the process to honour it, then SIGKILL ({@code destroyForcibly})
   * if it is still alive. The grace period matters: an honest build step that traps SIGTERM gets to
   * flush its log, finish its own child cleanup, and report a sensible exit code; a wedged step is
   * killed hard. Without the SIGTERM step the prior code went straight to SIGKILL and lost final
   * log output.
   *
   * <p>{@code descendants()} is a point-in-time snapshot at each level — a grandchild forked
   * <em>after</em> the enumeration is not signalled. Acceptable for short build steps; hermetic
   * termination would need an OS process group or a cgroup.
   */
  private static void terminateGracefully(Process proc, LogSink log) throws InterruptedException {
    log.system("titan: step cancelled — sending SIGTERM");
    proc.toHandle().descendants().forEach(ProcessHandle::destroy);
    proc.destroy();
    if (proc.waitFor(SIGTERM_GRACE_SECONDS, TimeUnit.SECONDS)) {
      log.system("titan: process exited cleanly after SIGTERM");
      return;
    }
    log.system(
        "titan: process did not exit within "
            + SIGTERM_GRACE_SECONDS
            + "s of SIGTERM — escalating to SIGKILL");
    proc.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
    proc.destroyForcibly();
    if (!proc.waitFor(5, TimeUnit.SECONDS)) {
      log.system("titan: process did not exit within 5s of SIGKILL");
    }
  }

  /**
   * Poll the cancel signal. A supplier failure — e.g. a transient DB error in the worker's {@code
   * () -> WorkerDb.isCancelled(taskId)} binding — is treated as "not cancelled" so a healthy step
   * is never killed by an unrelated hiccup.
   */
  private boolean cancelRequested(LogSink log) {
    try {
      return cancelled.getAsBoolean();
    } catch (RuntimeException e) {
      log.system("titan: cancellation check failed (" + e.getMessage() + ") — continuing");
      return false;
    }
  }

  /** Read a process stream line by line into the log sink. */
  private static void pump(InputStream in, LogSink log, String stream) {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        log.line(stream, line);
      }
    } catch (Exception e) {
      log.system("log pump (" + stream + ") failed: " + e.getMessage());
    }
  }
}
