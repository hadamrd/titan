package io.adaptiq.titan.worker.step;

/**
 * The Titan step SPI — the contract for one step type (Chunk 32A — design/32 §3).
 *
 * <p>Modelled on Netflix Orca's {@code Task}: pure data in, a status out, no framework leaking into
 * the implementation. A {@code StepHandler} has no {@code StepContext}, no {@code FlowExecution},
 * no CPS (design/29 §1). It is a plain object that, given a {@link StepRequest}, does work and
 * returns a {@link StepResult}.
 *
 * <p><strong>The contract.</strong>
 *
 * <ul>
 *   <li>{@link #execute} runs the step to completion on the worker. It returns {@code SUCCESS} /
 *       {@code FAILED}, or throws — a thrown handler is recorded {@code FAILED} with the message.
 *   <li>Outputs are published through {@link StepRequest#outputs()} <em>during</em> execution, so a
 *       step that fails part-way keeps what it produced (design/32 §4).
 *   <li>Retry is <em>not</em> the handler's concern — re-delivery is the queue's job (visibility
 *       timeout, {@code max_attempts}, the reaper).
 *   <li><strong>Idempotency is the handler author's contract.</strong> A reaped task is re-run from
 *       scratch (design/30 caveat 3), so {@code execute} must be safe to run more than once. The
 *       engine cannot enforce this — it is named here because it is real.
 *   <li>A handler must not reach past its {@link StepRequest} — no controller objects, no database,
 *       no worker internals. Its classloader enforces what is reachable (design/32 §6.3).
 * </ul>
 *
 * <p>Every handler — built-in or third-party — must pass the shared contract test kit (the TCK). A
 * handler that does not pass the TCK is not a handler.
 */
public interface StepHandler {

  /**
   * The step type this handler serves — e.g. {@code "sh"}, {@code "script"}, {@code "writeFile"}.
   * Must be unique across the registry and must equal {@code descriptor().descriptorId()}.
   */
  String descriptorId();

  /** Declarative metadata — drives the JSON schema and the authoring palette (design/32 §8). */
  StepDescriptor descriptor();

  /**
   * Run the step to completion.
   *
   * @param request the resolved inputs plus the log / output / executor collaborators
   * @return the terminal outcome
   * @throws Exception any failure — the worker records it as a {@code FAILED} step
   */
  StepResult execute(StepRequest request) throws Exception;
}
