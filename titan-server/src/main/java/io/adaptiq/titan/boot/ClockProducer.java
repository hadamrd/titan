package io.adaptiq.titan.boot;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.time.Clock;

/**
 * CDI producer for {@link Clock} — application-scoped wall clock, swap-out seam for tests.
 *
 * <p>Wired into rate limiters and any other time-sensitive bean that wants a controllable {@link
 * Clock} rather than calling {@link java.time.Instant#now()} directly. Production defaults to
 * {@link Clock#systemUTC()}; integration tests construct beans directly with a fixed/offset {@link
 * Clock} to advance virtual time deterministically.
 *
 * <p>Constructor-injection discipline; no field injection anywhere.
 */
@ApplicationScoped
public class ClockProducer {

  @Produces
  @ApplicationScoped
  Clock systemUtcClock() {
    return Clock.systemUTC();
  }
}
