package io.adaptiq.titan.timer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Pins the production scheduling wiring of the timer/approval sweep (#81). The whole bug class
 * behind #81 was a bean-less worker: {@code TimerSweepWorker} existed, ITs called it directly, and
 * NOTHING in production ever scheduled it — so the rot was invisible to every test. These
 * assertions make removing (or silently mis-configuring) the {@code @Scheduled} driver a test
 * failure, not a rig-only mystery.
 */
class TimerSweepSchedulerTest {

  @Test
  void schedulerIsADiscoverableBean() {
    assertNotNull(
        TimerSweepScheduler.class.getAnnotation(ApplicationScoped.class),
        "TimerSweepScheduler must be @ApplicationScoped or Quarkus never instantiates it "
            + "and the sweep is dead again (#81)");
  }

  @Test
  void tickCarriesTheScheduledAnnotation_withSkipConcurrency_and5sDefault() throws Exception {
    Method tick = TimerSweepScheduler.class.getMethod("tick");
    Scheduled scheduled = tick.getAnnotation(Scheduled.class);
    assertNotNull(
        scheduled,
        "tick() must be @Scheduled — without it TimerSweepWorker.sweep has no production "
            + "driver and approval timeouts never flip TIMED_OUT on any rig (#81)");
    assertEquals(
        "{quarkus.scheduler.titan.timer-sweep.every:5s}",
        scheduled.every(),
        "cadence must default to TimerSweepWorker.PERIOD_MS (5s) and stay operator-tunable");
    assertEquals(
        Scheduled.ConcurrentExecution.SKIP,
        scheduled.concurrentExecution(),
        "a slow sweep must skip-not-stack (repo bar for reapers)");
    assertEquals(5_000L, TimerSweepWorker.PERIOD_MS, "default cadence tracks the worker contract");
  }

  @Test
  void tickIsPublic_soQuarkusCanInvokeIt() throws Exception {
    Method tick = TimerSweepScheduler.class.getMethod("tick");
    assertTrue(java.lang.reflect.Modifier.isPublic(tick.getModifiers()));
    assertEquals(0, tick.getParameterCount(), "@Scheduled methods take no arguments");
  }
}
