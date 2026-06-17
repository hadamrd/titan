package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link WorkerMetricsSampler} (#348).
 *
 * <p>The sampler is the live-host metrics source called from the worker's heartbeat thread. We
 * cannot pin specific values (the test host's actual CPU/RAM/disk vary by machine) but we CAN
 * enforce the wire contract every caller depends on: any non-null reading is an integer in [0,
 * 100], and unavailable readings come back as {@code null} (never NaN, never -1, never a sentinel).
 *
 * <p>These tests also act as a guard against regressions where a refactor accidentally publishes a
 * negative percent from {@code getProcessCpuLoad()}'s "no measurement yet" return — the bug the
 * Sample contract is meant to hide from the UI.
 */
final class WorkerMetricsSamplerTest {

  @Test
  void sampleProducesPlausibleValuesOrNull(@TempDir Path tmp) {
    WorkerMetricsSampler sampler = new WorkerMetricsSampler(tmp.toFile());

    WorkerMetricsSampler.Sample s = sampler.sample();

    assertInRangeOrNull(s.cpuPct(), "cpuPct");
    assertInRangeOrNull(s.memPct(), "memPct");
    assertInRangeOrNull(s.diskPct(), "diskPct");
  }

  @Test
  void memoryPercentIsAlwaysAvailableOnHotSpot(@TempDir Path tmp) {
    // On every JDK Titan targets (HotSpot/OpenJDK), the com.sun OperatingSystemMXBean is
    // present and reports host physical-memory totals. If this assertion ever breaks, the
    // platform changed and the sampler contract needs to be re-examined — DON'T just delete
    // the test.
    WorkerMetricsSampler sampler = new WorkerMetricsSampler(tmp.toFile());

    Integer mem = sampler.memoryPercent();

    assertNotNull(mem, "memoryPercent must be readable on HotSpot/OpenJDK");
    assertInRange(mem, "memoryPercent");
  }

  @Test
  void diskPercentReadsTheWorkspaceVolume(@TempDir Path tmp) {
    WorkerMetricsSampler sampler = new WorkerMetricsSampler(tmp.toFile());

    Integer disk = sampler.diskPercent();

    // A real workspace volume always has non-zero total space, so disk MUST be non-null and
    // in [0, 100]. The null path is reserved for the explicit "JDK can't stat this volume"
    // case (a bogus File handle), not a temp dir.
    assertNotNull(disk, "diskPercent on a real volume must not be null");
    assertInRange(disk, "diskPercent");
  }

  @Test
  void diskPercentIsNullForBogusFile() throws Exception {
    // A File handle that doesn't resolve to any real volume reports total=0 from
    // getTotalSpace() — the documented "I don't know" reply. The sampler must turn that
    // into null, not divide by zero, not publish 100%.
    Path bogus = Files.createTempDirectory("titan-bogus-").resolve("does-not-exist-nor-volume");
    // Use a path that getTotalSpace() returns 0 for on most platforms — a child of a real
    // dir but with no actual mount underneath. If the JVM still returns a non-zero total
    // (some platforms inherit), we skip — this test is a contract guard, not a flaky check.
    File f = bogus.toFile();
    if (f.getTotalSpace() != 0L) {
      return; // platform doesn't expose a zero-total path; nothing to assert
    }
    WorkerMetricsSampler sampler = new WorkerMetricsSampler(f);

    assertNull(sampler.diskPercent(), "bogus volume must report null, not a fake 0/100");
  }

  private static void assertInRangeOrNull(Integer pct, String name) {
    if (pct == null) {
      return;
    }
    assertInRange(pct, name);
  }

  private static void assertInRange(int pct, String name) {
    assertTrue(pct >= 0 && pct <= 100, name + " out of range: " + pct);
  }
}
