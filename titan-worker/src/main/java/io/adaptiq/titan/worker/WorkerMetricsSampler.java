package io.adaptiq.titan.worker;

import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.lang.management.ManagementFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live host-resource sampler invoked from the heartbeat thread (#348).
 *
 * <p>JDK-only — no native agents, no /proc parsing. CPU comes from {@code
 * OperatingSystemMXBean#getProcessCpuLoad()}, memory from the {@code com.sun.management}
 * extension's free/total physical memory, disk from {@link File#getTotalSpace()} / {@link
 * File#getUsableSpace()} on the workspace volume. Any reading the JVM cannot supply comes back as
 * {@code null} — the UI renders that as an em-dash rather than a fake zero.
 *
 * <p>Returns small integer percents (0-100) so the wire DTO is a tiny JSON number, not a 17-digit
 * double. Each sample is a couple of MXBean lookups and a {@code statvfs} per disk — well under a
 * millisecond — so the heartbeat thread can sample inline on every beat without throttling.
 *
 * <p>{@code com.sun.management.OperatingSystemMXBean} is the JDK-standard HotSpot/OpenJDK extension
 * (present on all JDKs Titan targets) — using it is not a third-party dependency.
 */
final class WorkerMetricsSampler {

  private static final Logger LOG = LoggerFactory.getLogger(WorkerMetricsSampler.class);

  private final File workspaceRoot;
  private final OperatingSystemMXBean osBean;

  WorkerMetricsSampler(File workspaceRoot) {
    this.workspaceRoot = workspaceRoot;
    // The platform OS MXBean is always a com.sun.management.OperatingSystemMXBean on
    // HotSpot/OpenJDK. We hold a typed reference once so beat() never pays the cast cost
    // and a non-Sun JVM (the only platform where the cast would fail) gets a null bean
    // and reports null for both CPU and memory — no exception, no log spam per beat.
    OperatingSystemMXBean b = null;
    try {
      java.lang.management.OperatingSystemMXBean platform =
          ManagementFactory.getOperatingSystemMXBean();
      if (platform instanceof OperatingSystemMXBean sun) {
        b = sun;
      }
    } catch (RuntimeException e) {
      LOG.warn("OperatingSystemMXBean unavailable — CPU/MEM metrics will be null", e);
    }
    this.osBean = b;
  }

  /** A single point-in-time sample of CPU/MEM/DISK percentages. Any field may be null. */
  record Sample(Integer cpuPct, Integer memPct, Integer diskPct) {}

  /** Sample all three metrics. Never throws — a sampler failure must not break the heartbeat. */
  Sample sample() {
    return new Sample(cpuPercent(), memoryPercent(), diskPercent());
  }

  /**
   * Process CPU load as an integer percent, or null when the JVM cannot supply it.
   *
   * <p>The JDK contract for {@link OperatingSystemMXBean#getProcessCpuLoad()} returns a negative
   * value when no measurement is available — typically on the very first call before the OS
   * sampling window has populated. We treat any negative or NaN as null so the UI shows "no signal"
   * rather than a misleading 0%.
   */
  Integer cpuPercent() {
    if (osBean == null) {
      return null;
    }
    try {
      double load = osBean.getProcessCpuLoad();
      if (Double.isNaN(load) || load < 0.0) {
        return null;
      }
      return clamp((int) Math.round(load * 100.0));
    } catch (RuntimeException e) {
      LOG.debug("CPU sample failed", e);
      return null;
    }
  }

  /**
   * Resident memory usage as an integer percent of total physical memory, or null when the JVM
   * cannot supply host-memory totals.
   *
   * <p>Uses host-physical memory rather than the JVM heap so the bar reflects the worker host's
   * actual pressure (matching what an SRE would see in {@code top}), not just the JVM's own
   * residency — a worker whose heap is tiny but whose host is swapping is the failure mode we
   * actually want to surface.
   */
  Integer memoryPercent() {
    if (osBean == null) {
      return null;
    }
    try {
      long total = osBean.getTotalMemorySize();
      long free = osBean.getFreeMemorySize();
      if (total <= 0L || free < 0L || free > total) {
        return null;
      }
      long used = total - free;
      return clamp((int) Math.round(used * 100.0 / (double) total));
    } catch (RuntimeException e) {
      LOG.debug("memory sample failed", e);
      return null;
    }
  }

  /**
   * Disk usage percent of the workspace volume, or null when the JDK cannot stat it (e.g.
   * workspaceRoot does not exist yet, or {@code getTotalSpace()} returns 0 — the documented "I
   * don't know" reply).
   */
  Integer diskPercent() {
    try {
      long total = workspaceRoot.getTotalSpace();
      long usable = workspaceRoot.getUsableSpace();
      if (total <= 0L || usable < 0L || usable > total) {
        return null;
      }
      long used = total - usable;
      return clamp((int) Math.round(used * 100.0 / (double) total));
    } catch (SecurityException e) {
      LOG.debug("disk sample denied for {}", workspaceRoot, e);
      return null;
    }
  }

  private static int clamp(int pct) {
    if (pct < 0) {
      return 0;
    }
    return Math.min(pct, 100);
  }
}
