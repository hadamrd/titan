package io.adaptiq.titan.worker.step;

/**
 * The terminal outcome of a {@link StepHandler#execute} call (Chunk 32A — design/32 §3.1/§4).
 *
 * <p>Orca's {@code TaskResult} in Titan terms: a status plus the diagnostic detail. A step's
 * <em>outputs</em> are not here — they are published through the {@link OutputSink} during
 * execution (design/32 §4), so a step that fails part-way keeps what it produced.
 *
 * @param status {@link Status#SUCCESS} or {@link Status#FAILED}
 * @param exitCode the process exit code for a process-backed step, or {@code null}
 * @param message a human-readable detail — the failure reason, or {@code null} on success
 */
public record StepResult(Status status, Integer exitCode, String message) {

  /** Whether a step passed or failed. The queue maps a thrown handler to {@link #FAILED}. */
  public enum Status {
    SUCCESS,
    FAILED
  }

  /** A plain success with no process exit code. */
  public static StepResult success() {
    return new StepResult(Status.SUCCESS, null, null);
  }

  /** A success carrying a process exit code (necessarily {@code 0}). */
  public static StepResult success(int exitCode) {
    return new StepResult(Status.SUCCESS, exitCode, null);
  }

  /** A failure with a reason. */
  public static StepResult failed(String message) {
    return new StepResult(Status.FAILED, null, message);
  }

  /** A failure carrying a process exit code and a reason. */
  public static StepResult failed(int exitCode, String message) {
    return new StepResult(Status.FAILED, exitCode, message);
  }

  /** Map a process exit code to a result — {@code 0} is success, anything else a failure. */
  public static StepResult ofExitCode(int exitCode) {
    return exitCode == 0
        ? success(exitCode)
        : failed(exitCode, "process exited with code " + exitCode);
  }

  public boolean isSuccess() {
    return status == Status.SUCCESS;
  }
}
