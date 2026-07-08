package io.adaptiq.titan.trigger.engine;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the {@link TriggerSource} lifecycle on Quarkus (design/51 D3).
 *
 * <p>Every registered {@code TriggerSource} CDI bean is {@link TriggerSource#start() started} on
 * Quarkus {@link StartupEvent} and {@link TriggerSource#stop() stopped} on {@link ShutdownEvent}. A
 * failure in one source is isolated — it never blocks the others or the boot.
 *
 * <p>Lifecycle is driven by the Quarkus {@link StartupEvent}/{@link ShutdownEvent} pair.
 */
@ApplicationScoped
public final class TriggerSourceBootstrap {

  private static final Logger LOGGER = Logger.getLogger(TriggerSourceBootstrap.class.getName());

  @Inject Instance<TriggerSource> sources;

  /** Start every registered trigger source on Quarkus startup. */
  void onStart(@Observes StartupEvent event) {
    for (TriggerSource source : sources) {
      try {
        source.start();
        LOGGER.log(Level.INFO, "[trigger] started trigger source {0}", source.getClass().getName());
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "[trigger] trigger source " + source.getClass().getName() + " failed to start",
            e);
      }
    }
  }

  /** Stop every registered trigger source on Quarkus shutdown. */
  void onStop(@Observes ShutdownEvent event) {
    for (TriggerSource source : sources) {
      try {
        source.stop();
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "[trigger] trigger source " + source.getClass().getName() + " failed to stop",
            e);
      }
    }
  }
}
