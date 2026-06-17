package io.adaptiq.titan.worker.step;

/**
 * A sink for one step's log lines (Chunk 32A — design/32 §3.1).
 *
 * <p>A {@link StepHandler} writes to a {@code LogSink} instead of knowing how logs are stored. The
 * worker supplies a {@code titan.logs}-backed implementation; the TCK supplies a capturing one.
 * This is what keeps a handler free of worker internals — no task token, no chunk counter.
 */
public interface LogSink {

  /**
   * Write one log line.
   *
   * @param stream {@code "stdout"}, {@code "stderr"} or {@code "system"}
   * @param text the line (no trailing newline)
   */
  void line(String stream, String text);

  /** Write an engine/system line (worker diagnostics, not step output). */
  default void system(String text) {
    line("system", text);
  }
}
