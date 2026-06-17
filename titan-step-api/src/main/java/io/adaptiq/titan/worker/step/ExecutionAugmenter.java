package io.adaptiq.titan.worker.step;

/**
 * The execution-decoration SPI (design/42 §4.9).
 *
 * <p>{@code TaskExecutor} used to accrete a bespoke {@code if}-branch per execution-shaping scope:
 * design/39 bolted the credential-unseal / env-merge / secret-file / masking block straight into
 * it; design/41 bolted the {@code sshAgent} wrapper block in beside it. Each new "decorate a step's
 * execution" feature was more inline code in one method — the same monolith disease §4.7 identified
 * for the parser.
 *
 * <p>So execution decoration is decomposed behind this SPI. An augmenter is handed the pending step
 * as a mutable {@link StepExecutionContext} and may: add to the step {@code env}, materialise files
 * into the per-task workspace, wrap the step command, and register a post-step cleanup action — or
 * touch nothing at all (a no-op augmenter). {@code TaskExecutor} becomes a thin engine that loops
 * the discovered augmenters in {@link #order()}; {@code credentials} (design/39) and {@code
 * sshAgent} (design/41) are the first two built-in implementations.
 *
 * <p>Discovery is {@code ServiceLoader} (design/42 §3) — the same path as {@link
 * StepHandlerProvider} — via a {@code META-INF/services/io.adaptiq.titan.worker.step
 * .ExecutionAugmenter} line. There is no version gate for augmenters in v1: load and order.
 */
public interface ExecutionAugmenter {

  /**
   * Decorate the pending step — add env, materialise files, wrap the command, register a post-step
   * action. A no-op augmenter (the common case for any one step) touches nothing.
   *
   * @param ctx the mutable carrier for the step about to run
   */
  void augment(StepExecutionContext ctx);

  /**
   * The relative order this augmenter runs in — lower runs first; default {@code 0}. The {@code
   * credentials} augmenter orders before the {@code sshAgent} one so the latter can rely on the
   * credential env already being merged.
   *
   * @return the ordering key
   */
  default int order() {
    return 0;
  }
}
