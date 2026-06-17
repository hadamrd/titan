package io.adaptiq.titan.worker.step;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The <em>environment</em> axis of step execution — where a command's process runs (Chunk 32A,
 * design/31 §6G / design/32 §1). Distinct from the <em>semantics</em> axis ({@link StepHandler}): a
 * handler decides <em>what</em> command to run; a {@code StepExecutor} decides the sandbox it runs
 * in.
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@code LocalProcessExecutor} — a process on the worker host.
 *   <li>{@code ContainerExecutor} — a process inside a declared container image.
 *   <li>a future {@code PodExecutor} — an ephemeral Kubernetes pod, on this same SPI.
 * </ul>
 *
 * <p>The worker selects the implementation per task (from the payload's {@code image}) and injects
 * it into the {@link StepRequest}; a process-backed {@link StepHandler} runs commands through it.
 * The orchestrator and the DAG never see this — containerisation is purely worker-local.
 */
public interface StepExecutor {

  /**
   * Run {@code command} to completion, streaming its output to {@code log}.
   *
   * @param command the command and its arguments
   * @param workDir the working directory
   * @param env the step environment — built from the task payload only, never the worker's own
   *     environment (design/26 Tier C)
   * @param log the sink for the process's stdout/stderr
   * @return the process exit code
   * @throws Exception if the process could not be started or supervised (an infrastructure failure
   *     — distinct from the process running and exiting non-zero)
   */
  default int run(List<String> command, Path workDir, Map<String, String> env, LogSink log)
      throws Exception {
    return run(command, workDir, env, log, null);
  }

  /**
   * Run {@code command} to completion, streaming its output to {@code log}, echoing {@code
   * displayCommand} as the {@code $ …} command line instead of the literal {@code argv}.
   *
   * <p>This exists for steps whose real {@code argv} is worker-injected plumbing the user must not
   * see — notably an {@code sshAgent:}-wrapped {@code sh} step (design/41 §4), whose {@code sh -c}
   * argument is an ssh-agent lifecycle preamble around the user's own script. The preamble executes
   * unchanged; only the echoed line is the user's original script. A {@code null} {@code
   * displayCommand} echoes the literal {@code command} — the normal case, including a plain
   * multi-line {@code sh:} script the user did write.
   *
   * @param command the command and its arguments — what actually runs
   * @param workDir the working directory
   * @param env the step environment (design/26 Tier C)
   * @param log the sink for the process's stdout/stderr
   * @param displayCommand the text to echo as the {@code $ …} line, or {@code null} to echo the
   *     literal {@code command}
   * @return the process exit code
   * @throws Exception if the process could not be started or supervised
   */
  int run(
      List<String> command,
      Path workDir,
      Map<String, String> env,
      LogSink log,
      String displayCommand)
      throws Exception;
}
