package io.adaptiq.titan.worker.step;

/**
 * The Titan step-SPI version handshake (design/42 §4.4).
 *
 * <p>{@code titan-step-api} exposes {@link #VERSION} — an {@code int}, currently {@code 1}. At
 * discovery the worker compares it to each provider's declared {@code apiVersion()}:
 *
 * <ul>
 *   <li>equal &rarr; load;
 *   <li>provider older, within a declared compatibility floor &rarr; load, log INFO;
 *   <li>provider newer than the worker, or below the floor &rarr; reject that provider, log WARNING
 *       with the exact mismatch — its steps are simply absent, so a pipeline using one fails
 *       cleanly at "unknown step", never with a {@code NoSuchMethodError} mid-build.
 * </ul>
 *
 * <p>The version bumps only on a <strong>breaking</strong> SPI change; additive changes (a new
 * optional {@link ParamSpec} field, a new default method) do not bump it.
 */
public final class StepApi {

  /** The current step-SPI generation. Bumped only on a breaking SPI change (design/42 §4.4). */
  public static final int VERSION = 1;

  private StepApi() {
    // constant holder — not instantiable
  }
}
