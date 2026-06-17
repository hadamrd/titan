package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.JobTimingsDao;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JobTimingsHandler}'s validation + percentile-math reference helper.
 *
 * <p>The handler's SQL aggregate ({@code percentile_cont}) is exercised end-to-end in {@code
 * JobTimingsApiIT}. These tests pin two contracts that don't need Postgres:
 *
 * <ul>
 *   <li>The closed-set validation of {@code n} ({@code &lt;=0} and {@code &gt;100} both 400).
 *   <li>A pure-Java reference implementation of {@code percentile_cont} that matches NumPy's
 *       default {@code numpy.percentile(method='linear')}. The IT then asserts the SQL output
 *       agrees with this reference on the same input — the failure of either side would surface the
 *       math drift the issue calls out ("unit: percentile calc on a synthesised distribution
 *       matches NumPy's percentile").
 * </ul>
 */
class JobTimingsHandlerTest {

  // ── validateN ─────────────────────────────────────────────────────────────

  @Test
  void validateN_acceptsPositiveInRange() {
    assertEquals(1, JobTimingsHandler.validateN(1));
    assertEquals(30, JobTimingsHandler.validateN(30));
    assertEquals(JobTimingsDao.MAX_BUILDS, JobTimingsHandler.validateN(JobTimingsDao.MAX_BUILDS));
  }

  @Test
  void validateN_zeroOrNegative_throws400() {
    ApiBadRequestException ex0 =
        assertThrows(ApiBadRequestException.class, () -> JobTimingsHandler.validateN(0));
    assertTrue(ex0.getMessage().contains("'n'"), "error message names the offending param");
    assertThrows(ApiBadRequestException.class, () -> JobTimingsHandler.validateN(-1));
    assertThrows(ApiBadRequestException.class, () -> JobTimingsHandler.validateN(-9999));
  }

  @Test
  void validateN_aboveMax_throws400() {
    int over = JobTimingsDao.MAX_BUILDS + 1;
    ApiBadRequestException ex =
        assertThrows(ApiBadRequestException.class, () -> JobTimingsHandler.validateN(over));
    assertTrue(
        ex.getMessage().contains(Integer.toString(JobTimingsDao.MAX_BUILDS)),
        "error message reports the cap");
  }

  // ── percentile_cont reference (matches numpy.percentile(method='linear')) ──

  /**
   * NumPy's default {@code percentile} (and PostgreSQL's {@code percentile_cont}) compute the
   * sample at fractional index {@code (n - 1) * p}, linearly interpolating between the two
   * surrounding sorted samples.
   *
   * <p>This is the SAME algorithm Postgres ships — so a unit test here pins the value the SQL MUST
   * produce on identical input. The IT asserts equality against this helper on a fresh Postgres
   * instance, catching any future regression in either direction.
   */
  static double percentileLinear(List<Long> samples, double p) {
    if (samples.isEmpty()) {
      throw new IllegalArgumentException("empty samples");
    }
    if (p < 0.0 || p > 1.0) {
      throw new IllegalArgumentException("p out of [0,1]: " + p);
    }
    List<Long> sorted = new ArrayList<>(samples);
    sorted.sort(Long::compareTo);
    double idx = (sorted.size() - 1) * p;
    int lo = (int) Math.floor(idx);
    int hi = (int) Math.ceil(idx);
    if (lo == hi) {
      return (double) sorted.get(lo);
    }
    double frac = idx - lo;
    return sorted.get(lo) + frac * (sorted.get(hi) - sorted.get(lo));
  }

  @Test
  void percentileLinear_singleSample_returnsThatSample() {
    // Postgres returns the single value for any percentile in a single-row group.
    assertEquals(42.0, percentileLinear(List.of(42L), 0.5), 1e-9);
    assertEquals(42.0, percentileLinear(List.of(42L), 0.99), 1e-9);
  }

  @Test
  void percentileLinear_twoSamples_p50IsMean() {
    // numpy.percentile([10, 20], 50) → 15.0
    assertEquals(15.0, percentileLinear(Arrays.asList(10L, 20L), 0.5), 1e-9);
  }

  @Test
  void percentileLinear_knownDistribution_matchesNumpy() {
    // The reference values below come from:
    //   >>> import numpy as np
    //   >>> a = [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]
    //   >>> np.percentile(a, [50, 95, 99])
    //   array([ 550. ,  955. ,  991. ])
    List<Long> dist = Arrays.asList(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L, 900L, 1000L);
    assertEquals(550.0, percentileLinear(dist, 0.50), 1e-9);
    assertEquals(955.0, percentileLinear(dist, 0.95), 1e-9);
    assertEquals(991.0, percentileLinear(dist, 0.99), 1e-9);
  }

  @Test
  void percentileLinear_unsortedInput_sortsBeforeComputing() {
    // Same distribution as above, scrambled. Percentile MUST be invariant to input ordering —
    // an SRE shouldn't get a different p95 because builds arrived in a different order.
    List<Long> scrambled =
        Arrays.asList(700L, 100L, 1000L, 300L, 500L, 200L, 900L, 400L, 800L, 600L);
    assertEquals(550.0, percentileLinear(scrambled, 0.50), 1e-9);
    assertEquals(955.0, percentileLinear(scrambled, 0.95), 1e-9);
  }

  @Test
  void percentileLinear_emptyOrOutOfRange_throws() {
    assertThrows(IllegalArgumentException.class, () -> percentileLinear(List.of(), 0.5));
    assertThrows(IllegalArgumentException.class, () -> percentileLinear(List.of(1L), -0.01));
    assertThrows(IllegalArgumentException.class, () -> percentileLinear(List.of(1L), 1.01));
  }
}
