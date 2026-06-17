package io.adaptiq.titan.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A faithful minimal worker thread for the chaos rig — claims EXECUTE_COMMAND tasks, runs the
 * step's shell command as a real subprocess, completes the task, and records the run in the ledger.
 * Honors a pause flag so the monkey can pause it mid-step, and a {@code crashSignal} counter so the
 * monkey can simulate a worker crash: when non-zero the worker claims a task and then "dies"
 * without running it, leaving the task CLAIMED. The reaper must recover it.
 *
 * <p>The production worker ({@code titan-worker}'s {@code WorkerDb}/{@code TaskExecutor}) is
 * package-private in another module and unreachable from here; this is a deliberately minimal
 * stand-in — real process, real exit code, real DB claim/complete contention — sufficient for the
 * rig's controller/DB/timer chaos focus.
 */
final class ChaosWorker implements Runnable {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** Hard wall-clock cap so a hung step cannot wedge the rig. Not the engine's timeout path. */
  private static final long SUBPROCESS_CAP_SECONDS = 30L;

  /** Exit code reported when the rig safety-net cap kills a subprocess. */
  private static final int CAP_EXIT_CODE = 124;

  private final String agentId;
  private final TitanStores stores;
  private final ChaosLedger ledger;
  private final AtomicBoolean running;
  private final AtomicBoolean paused;

  /**
   * Crash-signal counter. When positive, the worker claims the next task and then "dies" without
   * running or completing it — leaving the task CLAIMED. The counter self-decrements on each
   * abandoned claim. The reaper must recover the stranded task.
   */
  private final AtomicInteger crashSignal;

  ChaosWorker(
      String agentId,
      TitanStores stores,
      ChaosLedger ledger,
      AtomicBoolean running,
      AtomicBoolean paused,
      AtomicInteger crashSignal) {
    this.agentId = agentId;
    this.stores = stores;
    this.ledger = ledger;
    this.running = running;
    this.paused = paused;
    this.crashSignal = crashSignal;
  }

  @Override
  public void run() {
    while (running.get()) {
      try {
        if (paused.get()) {
          Thread.sleep(50L);
          continue;
        }
        Optional<TaskQueueRow> claimed =
            stores.taskQueue().claimExecuteCommand(agentId, "chaos", UUID.randomUUID());
        if (claimed.isEmpty()) {
          Thread.sleep(50L);
          continue;
        }
        TaskQueueRow t = claimed.get();
        // Simulated crash: a pending crash signal means this worker "dies" right after
        // claiming — it neither runs nor completes the task, leaving it CLAIMED. The reaper
        // must recover it.
        if (crashSignal.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0) {
          continue;
        }
        int exit = runRealSubprocess(t.payloadJson);
        ledger.record(t.id);
        stores.taskQueue().complete(t.id, t.claimToken, "COMPLETED", "{\"exitCode\":" + exit + "}");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      } catch (RuntimeException e) {
        // A transient DB fault (Toxiproxy) surfaces here — drop the task; its claim
        // expires and another worker reclaims it. That is the at-least-once contract.
      }
    }
  }

  /**
   * Parse an EXECUTE_COMMAND payload's {@code command} array — a ready-to-run {@code
   * ["sh","-c","<script>"]} list — and run it as a real subprocess, returning its exit code. An
   * empty/absent command array (a non-sh step) is a no-op success.
   */
  static int runRealSubprocess(String payloadJson) {
    List<String> command = extractCommand(payloadJson);
    if (command.isEmpty()) {
      return 0;
    }
    Process proc = null;
    try {
      proc = new ProcessBuilder(command).redirectErrorStream(true).start();
      if (!proc.waitFor(SUBPROCESS_CAP_SECONDS, TimeUnit.SECONDS)) {
        proc.destroyForcibly();
        return CAP_EXIT_CODE;
      }
      return proc.exitValue();
    } catch (InterruptedException e) {
      if (proc != null) {
        proc.destroyForcibly();
      }
      Thread.currentThread().interrupt();
      return CAP_EXIT_CODE;
    } catch (Exception e) {
      throw new AssertionError("runRealSubprocess failed for payload: " + payloadJson, e);
    }
  }

  /** Pull the {@code command} array out of an EXECUTE_COMMAND payload. */
  private static List<String> extractCommand(String payloadJson) {
    try {
      JsonNode root = JSON.readTree(payloadJson);
      JsonNode cmd = root.path("command");
      List<String> command = new ArrayList<>();
      if (cmd.isArray()) {
        cmd.forEach(n -> command.add(n.asText()));
      }
      return command;
    } catch (Exception e) {
      throw new AssertionError("could not parse EXECUTE_COMMAND payload: " + payloadJson, e);
    }
  }

  /** Test helper — build a payload in the orchestrator's EXECUTE_COMMAND shape. */
  static String payloadFor(String shCommand) {
    try {
      return JSON.writeValueAsString(Map.of("command", List.of("sh", "-c", shCommand)));
    } catch (Exception e) {
      throw new AssertionError("could not build test payload", e);
    }
  }
}
