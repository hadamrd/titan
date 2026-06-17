package io.adaptiq.titan.worker.step.augment;

import io.adaptiq.titan.worker.step.ExecutionAugmenter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovers {@link ExecutionAugmenter}s by {@code ServiceLoader} and orders them (design/42 §4.9).
 *
 * <p>The same JDK discovery path as {@link io.adaptiq.titan.worker.step.StepHandlerProvider} — a
 * {@code META-INF/services/io.adaptiq.titan.worker.step.ExecutionAugmenter} line. The two
 * built-ins, {@code CredentialsAugmenter} and {@code SshAgentAugmenter}, are registered there; the
 * socle dogfoods the SPI. There is no version gate for augmenters in v1: load and order.
 *
 * <p>A throwing augmenter at instantiation is skipped with a WARNING — one malformed augmenter
 * cannot take the worker down — mirroring the step-provider discovery contract (§4.3 rule 4).
 */
public final class ExecutionAugmenterDiscovery {

  private static final Logger LOG = LoggerFactory.getLogger(ExecutionAugmenterDiscovery.class);

  private ExecutionAugmenterDiscovery() {
    // static-only
  }

  /**
   * Discover every {@link ExecutionAugmenter} on the worker classpath, ordered by {@link
   * ExecutionAugmenter#order()} (lower first); ties keep discovery order (a stable sort).
   *
   * @return the ordered augmenter list — never {@code null}, possibly empty
   */
  public static List<ExecutionAugmenter> discover() {
    List<ExecutionAugmenter> augmenters = new ArrayList<>();
    ServiceLoader<ExecutionAugmenter> loader =
        ServiceLoader.load(
            ExecutionAugmenter.class, ExecutionAugmenterDiscovery.class.getClassLoader());
    var it = loader.iterator();
    while (true) {
      ExecutionAugmenter augmenter;
      try {
        if (!it.hasNext()) {
          break;
        }
        augmenter = it.next();
      } catch (ServiceConfigurationError | RuntimeException e) {
        LOG.warn("augmenter discovery: an augmenter failed to load — skipping: {}", e.toString());
        continue;
      }
      augmenters.add(augmenter);
    }
    augmenters.sort(Comparator.comparingInt(ExecutionAugmenter::order));
    if (LOG.isInfoEnabled()) {
      List<String> names = new ArrayList<>();
      augmenters.forEach(a -> names.add(a.getClass().getSimpleName() + "@" + a.order()));
      LOG.info(
          "augmenter discovery: loaded {} execution augmenter(s): {}", augmenters.size(), names);
    }
    return augmenters;
  }
}
