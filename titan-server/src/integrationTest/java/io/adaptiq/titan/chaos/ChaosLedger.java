package io.adaptiq.titan.chaos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An append-only execution ledger for the chaos rig. Every EXECUTE_COMMAND subprocess run is
 * recorded by {@code task_queue} row id. Kept in memory (a concurrent counter) and mirrored to a
 * file so a crashed run can still be inspected. The at-least-once queue permits a task to run more
 * than once; the ledger exists to prove every task ran <em>at least</em> once and to surface the
 * re-execution rate, never to fail on re-execution.
 */
final class ChaosLedger {

  private final Path file;
  private final Map<Long, AtomicLong> counts = new ConcurrentHashMap<>();

  ChaosLedger(Path dir) {
    try {
      Files.createDirectories(dir);
      this.file = dir.resolve("chaos-ledger.log");
      Files.write(
          file, new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    } catch (IOException e) {
      throw new UncheckedIOException("could not create chaos ledger", e);
    }
  }

  /** Record one subprocess execution of the given task. Thread-safe. */
  void record(long taskId) {
    counts.computeIfAbsent(taskId, k -> new AtomicLong()).incrementAndGet();
    synchronized (this) {
      try {
        Files.writeString(file, taskId + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
      } catch (IOException e) {
        throw new UncheckedIOException("ledger append failed", e);
      }
    }
  }

  /** Immutable snapshot: task id -> number of times its subprocess ran. */
  Map<Long, Long> executionCounts() {
    Map<Long, Long> snapshot = new java.util.HashMap<>();
    counts.forEach((k, v) -> snapshot.put(k, v.get()));
    return Map.copyOf(snapshot);
  }
}
