package io.adaptiq.titan.audit;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.AuditRetentionPolicyRow;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable, in-memory view of {@code titan.audit_retention_policy}: resolves the retention horizon
 * (in days) for a given audit action kind (closes #1104).
 *
 * <p>Resolution rule — <strong>kind-specific &gt; default</strong>:
 *
 * <ol>
 *   <li>If a row exists for the exact {@code kind}, use its {@code max_age_days}.
 *   <li>Otherwise use the {@code "*"} default row's value.
 *   <li>If neither is present (empty table), fall back to {@link #DEFAULT_MAX_AGE_DAYS} (90) — the
 *       feature degrades to a sane default rather than disabling retention.
 * </ol>
 *
 * <p>Any resolved value {@code <= 0} raises {@link InvalidRetentionPolicyException}: a 0-day policy
 * would purge a kind's entire history, which is a misconfiguration, not a feature.
 */
public final class AuditRetentionPolicy {

  /** Reserved sentinel kind carrying the server-wide default. Never a real action code. */
  public static final String DEFAULT_KEY = "*";

  /** Hard fallback when the table has no default row at all. */
  public static final int DEFAULT_MAX_AGE_DAYS = 90;

  private final Map<String, Integer> byKind;

  private AuditRetentionPolicy(@NonNull Map<String, Integer> byKind) {
    this.byKind = byKind;
  }

  /** Build a policy view from the raw rows (as returned by {@code AuditRetentionPolicyDao}). */
  @NonNull
  public static AuditRetentionPolicy of(@NonNull Iterable<AuditRetentionPolicyRow> rows) {
    Map<String, Integer> map = new HashMap<>();
    for (AuditRetentionPolicyRow row : rows) {
      if (row != null && row.kind != null) {
        map.put(row.kind, row.maxAgeDays);
      }
    }
    return new AuditRetentionPolicy(map);
  }

  /**
   * Resolve the retention horizon in days for one action kind. Kind-specific wins over the default.
   *
   * @throws InvalidRetentionPolicyException if the resolved horizon is {@code <= 0}
   */
  public int resolve(@NonNull String kind) {
    Integer specific = byKind.get(kind);
    int days = specific != null ? specific : byKind.getOrDefault(DEFAULT_KEY, DEFAULT_MAX_AGE_DAYS);
    if (days <= 0) {
      throw new InvalidRetentionPolicyException(kind, days);
    }
    return days;
  }

  /** The default horizon ({@code "*"} row, else {@link #DEFAULT_MAX_AGE_DAYS}). */
  public int defaultMaxAgeDays() {
    return byKind.getOrDefault(DEFAULT_KEY, DEFAULT_MAX_AGE_DAYS);
  }
}
