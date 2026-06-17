package io.adaptiq.titan.audit;

/**
 * Thrown when an audit-log retention policy resolves to a non-positive horizon (closes #1104).
 *
 * <p>A {@code max_age_days <= 0} policy would purge the entire history of a kind on the next sweep
 * — that is an operator misconfiguration, not a feature. The condition is caught in three places,
 * so this typed error carries the offending kind + value for whichever layer surfaces it:
 *
 * <ul>
 *   <li>the override endpoint rejects it with HTTP 400 before it ever reaches the DB;
 *   <li>the DB CHECK constraint refuses the row (last line of defence against a manual edit);
 *   <li>{@link AuditRetentionPolicy#resolve(String)} raises this if a non-positive value somehow
 *       lands in the table, so the nightly job skips that kind loudly rather than deleting
 *       everything.
 * </ul>
 */
public class InvalidRetentionPolicyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String kind;
  private final int maxAgeDays;

  public InvalidRetentionPolicyException(String kind, int maxAgeDays) {
    super(
        "audit retention policy for kind '"
            + kind
            + "' has non-positive max_age_days="
            + maxAgeDays
            + " (must be >= 1; a 0-day policy would purge the entire history of the kind)");
    this.kind = kind;
    this.maxAgeDays = maxAgeDays;
  }

  public String kind() {
    return kind;
  }

  public int maxAgeDays() {
    return maxAgeDays;
  }
}
