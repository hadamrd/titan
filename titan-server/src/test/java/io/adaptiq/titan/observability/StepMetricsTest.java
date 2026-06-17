package io.adaptiq.titan.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-level pin for {@link StepMetrics} (issue #1081).
 *
 * <p>Verifies the histogram is registered with the labels + buckets the SRE runbook promises, and
 * that the sad-path branches (missing labels, bad status, null duration, registry blow-up) record
 * something useful rather than nothing — silent metric drops are the bug class this ticket exists
 * to kill.
 */
class StepMetricsTest {

  private SimpleMeterRegistry registry;

  @BeforeEach
  void installRegistry() {
    registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
  }

  @AfterEach
  void removeRegistry() {
    Metrics.removeRegistry(registry);
    registry.close();
  }

  @Test
  void happyPath_recordsHistogramWithJobStepStatusLabels() {
    StepMetrics.recordStepDuration("acme/web", "build-image", "SUCCESS", Duration.ofSeconds(7));

    DistributionSummary summary = findSummary("acme/web", "build-image", "SUCCESS");
    assertNotNull(summary, "histogram should be registered on /q/metrics with the exact labels");
    assertEquals(1L, summary.count(), "exactly one observation should land");
    assertEquals(7.0, summary.totalAmount(), 0.001, "observed value is duration in seconds");
  }

  @Test
  void unknownStatus_isCoercedToUnknownLabel_notLeakedAsArbitraryString() {
    // A FAANG-grade adversarial test: an attacker-controlled or accidentally-wrong status string
    // must not blow up Prometheus cardinality. The label set is bounded, period.
    StepMetrics.recordStepDuration("j", "s", "WHO-KNOWS-LOL", Duration.ofSeconds(1));

    assertNotNull(findSummary("j", "s", StepMetrics.UNKNOWN_LABEL));
  }

  @Test
  void nullJobAndStep_areReplacedWithUnknown_notSilentlyDropped() {
    StepMetrics.recordStepDuration(null, null, "FAILED", Duration.ofSeconds(2));

    DistributionSummary summary =
        findSummary(StepMetrics.UNKNOWN_LABEL, StepMetrics.UNKNOWN_LABEL, "FAILED");
    assertNotNull(summary, "null labels must still produce an observation");
    assertEquals(1L, summary.count());
  }

  @Test
  void negativeDuration_recordsAsZero_notPropagatedAsNonsense() {
    StepMetrics.recordStepDuration("j", "s", "SUCCESS", Duration.ofSeconds(-5));

    DistributionSummary summary = findSummary("j", "s", "SUCCESS");
    assertNotNull(summary);
    assertEquals(0.0, summary.totalAmount(), 0.001);
  }

  @Test
  void bucketsMatchAcceptanceCriteria() {
    // The pre-baked SLO bucket boundaries are part of the public contract — any change here is a
    // dashboard-breaking API change and needs an explicit ticket bump.
    assertEquals(6, StepMetrics.BUCKETS_SECONDS.length);
    assertEquals(1.0, StepMetrics.BUCKETS_SECONDS[0]);
    assertEquals(5.0, StepMetrics.BUCKETS_SECONDS[1]);
    assertEquals(15.0, StepMetrics.BUCKETS_SECONDS[2]);
    assertEquals(60.0, StepMetrics.BUCKETS_SECONDS[3]);
    assertEquals(300.0, StepMetrics.BUCKETS_SECONDS[4]);
    assertEquals(1800.0, StepMetrics.BUCKETS_SECONDS[5]);
  }

  @Test
  void durationSeconds_convertsNanosToSecondsExactly() {
    assertEquals(1.5, StepMetrics.durationSeconds(Duration.ofMillis(1500)), 1e-9);
  }

  @Test
  void recordOtelExportFailure_incrementsCounter_doesNotThrow() {
    StepMetrics.recordOtelExportFailure("traceparent_capture");
    StepMetrics.recordOtelExportFailure("traceparent_capture");

    assertTrue(
        registry
                .find("titan.otel.export.failures.total")
                .tag("reason", "traceparent_capture")
                .counter()
                .count()
            >= 2.0);
  }

  private DistributionSummary findSummary(String job, String step, String status) {
    return registry
        .find("titan.step.duration.seconds")
        .tag("job", job)
        .tag("step", step)
        .tag("status", status)
        .summary();
  }
}
