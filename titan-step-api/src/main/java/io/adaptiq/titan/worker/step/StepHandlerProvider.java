package io.adaptiq.titan.worker.step;

import java.util.List;

/**
 * The discovered step-SPI unit (design/42 §4.2).
 *
 * <p>{@code ServiceLoader} needs a public no-arg constructor, but step handlers do not all have one
 * — {@code LibraryStepHandler(libraryCacheRoot)}, {@code GitStepHandler(id)} — and a single jar
 * often ships a <em>family</em> of steps. So the discovered unit is a <strong>provider</strong>: it
 * is given the worker's {@link StepHandlerContext} and returns the handlers it contributes.
 *
 * <p>A provider is registered the JDK way — a {@code META-INF/services/io.adaptiq.titan
 * .worker.step.StepHandlerProvider} line. The socle's own handlers are discovered through this
 * exact path (design/42 §2 — "the socle dogfoods the SPI"); there is no privileged built-in code
 * path.
 */
public interface StepHandlerProvider {

  /**
   * The handlers this provider contributes, given the worker's environment.
   *
   * @param context the narrow worker state — shared-libraries root, SPI version, a logger
   * @return the handlers to register; never {@code null}
   */
  List<StepHandler> handlers(StepHandlerContext context);

  /** Non-sensitive, human-readable — for the startup audit log (design/42 §4.3 rule 5). */
  String describe();

  /**
   * The step-SPI generation this provider was built against — drives the version gate (design/42
   * §4.4). Default-methoded so a provider built against this generation need not declare it.
   */
  default int apiVersion() {
    return StepApi.VERSION;
  }
}
