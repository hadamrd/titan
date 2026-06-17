package io.adaptiq.titan.trigger.engine;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level fake source for {@link TriggerSourceBootstrapTest} — placed at top level so the
 * Quarkus/Arc indexer reliably discovers it as an {@code @ApplicationScoped} CDI bean.
 *
 * <p>State is held in an {@link AtomicBoolean} accessed through {@link #wasStarted()} / {@link
 * #wasStopped()} methods (NOT plain fields) because Arc client proxies are subclasses with their
 * own field slots — reading a {@code boolean} field through the proxy reads the proxy's slot, not
 * the underlying bean's. Method calls correctly delegate.
 */
@Unremovable
@ApplicationScoped
public class FakeBootstrapSource implements TriggerSource {

  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean stopped = new AtomicBoolean();

  @Override
  public void start() {
    started.set(true);
  }

  @Override
  public void stop() {
    stopped.set(true);
  }

  public boolean wasStarted() {
    return started.get();
  }

  public boolean wasStopped() {
    return stopped.get();
  }
}
