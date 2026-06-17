package io.adaptiq.titan.worker;

import io.adaptiq.titan.worker.step.LogSink;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The worker's {@link LogSink} — streams a step's log lines into {@code titan.logs}, chunk by
 * chunk, keyed by the task token (Chunk 32A).
 *
 * <p>It owns the chunk counter and the task token so a {@link
 * io.adaptiq.titan.worker.step.StepHandler} never has to — that is what keeps a handler free of
 * worker internals. A logging failure is swallowed (best-effort): it must never abort a step.
 */
final class DbLogSink implements LogSink {

  private static final Logger LOG = LoggerFactory.getLogger(DbLogSink.class);

  private final WorkerDb db;
  private final UUID taskToken;
  private final AtomicInteger chunk = new AtomicInteger();

  DbLogSink(WorkerDb db, UUID taskToken) {
    this.db = db;
    this.taskToken = taskToken;
  }

  @Override
  public void line(String stream, String text) {
    write(stream, "system".equals(stream) ? "[titan-worker] " + text : text, false);
  }

  /** Write the terminal log line for the task — marks the stream {@code is_final}. */
  void finish(String message) {
    write("system", "[titan-worker] " + message, true);
  }

  private void write(String stream, String data, boolean isFinal) {
    try {
      db.appendLog(taskToken, chunk.getAndIncrement(), stream, data, isFinal);
    } catch (Exception e) {
      LOG.warn("appendLog for task {} failed", taskToken, e);
    }
  }
}
