package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.AuditRetentionPolicyRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.audit_retention_policy} — per-event-kind audit-log
 * retention horizons (closes #1104).
 *
 * <p>The table is tiny (one row per configured kind plus the {@code "*"} default), so reads load
 * the whole set; the {@link io.adaptiq.titan.audit.RetentionJob} resolves per-kind cutoffs from it
 * in memory.
 *
 * <p>{@link #upsert(String, int)} is portable across H2 and PostgreSQL — it tries an UPDATE first
 * and falls back to an INSERT, avoiding the dialect-specific {@code ON CONFLICT} / {@code MERGE}
 * grammar. The UPDATE→INSERT window is a TOCTOU race (two concurrent overrides of the same kind can
 * both see UPDATE→0 and both attempt the INSERT); the loser hits the PK constraint. {@code upsert}
 * recovers from that by re-running the UPDATE on INSERT failure, so a concurrent override surfaces
 * as last-write-wins (the same outcome a serialized pair would produce) rather than an unhandled
 * 500 — see the method javadoc.
 */
@RegisterFieldMapper(AuditRetentionPolicyRow.class)
public interface AuditRetentionPolicyDao extends SqlObject {

  /** All policy rows, including the {@code "*"} default sentinel. */
  @SqlQuery("SELECT kind, max_age_days, updated_at FROM titan.audit_retention_policy")
  @NonNull
  List<AuditRetentionPolicyRow> findAll();

  /** The policy row for one kind, or empty when the kind has no override. */
  @SqlQuery(
      "SELECT kind, max_age_days, updated_at FROM titan.audit_retention_policy WHERE kind = :kind")
  @NonNull
  Optional<AuditRetentionPolicyRow> findByKind(@Bind("kind") @NonNull String kind);

  /** UPDATE an existing policy row; returns rows affected (0 when the kind is absent). */
  @SqlUpdate(
      "UPDATE titan.audit_retention_policy "
          + "SET max_age_days = :maxAgeDays, updated_at = CURRENT_TIMESTAMP WHERE kind = :kind")
  int update(@Bind("kind") @NonNull String kind, @Bind("maxAgeDays") int maxAgeDays);

  /** INSERT a new policy row. */
  @SqlUpdate(
      "INSERT INTO titan.audit_retention_policy (kind, max_age_days) VALUES (:kind, :maxAgeDays)")
  void insert(@Bind("kind") @NonNull String kind, @Bind("maxAgeDays") int maxAgeDays);

  /**
   * Insert-or-update one kind's retention horizon. Portable across H2 and PostgreSQL (UPDATE then
   * INSERT-on-miss rather than dialect-specific upsert grammar).
   *
   * <p>TOCTOU-safe: if two writers race the same kind, both can observe {@code update == 0} and
   * both attempt the INSERT — the loser fails the {@code kind} PK constraint. Rather than let that
   * surface as an unhandled 500, the failed INSERT is recovered by re-running the UPDATE: the row
   * now exists (the winner inserted it), so the UPDATE affects one row and this writer's value wins
   * (last-write-wins, identical to a serialized pair of upserts). The recovery is guarded — if the
   * retry UPDATE still affects 0 rows the original failure was NOT a lost INSERT race (e.g. a CHECK
   * violation on a bad {@code maxAgeDays}), so the original exception is rethrown unchanged.
   *
   * @param kind the audit action code, or {@code "*"} for the default
   * @param maxAgeDays retention horizon in days; callers MUST validate {@code > 0} (the DB CHECK is
   *     the last line of defence, the API guard is the first)
   */
  default void upsert(@NonNull String kind, int maxAgeDays) {
    if (update(kind, maxAgeDays) != 0) {
      return;
    }
    try {
      insert(kind, maxAgeDays);
    } catch (RuntimeException insertFailed) {
      // A concurrent writer may have inserted this kind between our UPDATE→0 and INSERT. Re-run the
      // UPDATE: if the row now exists this wins; if it still affects nothing the failure was not a
      // lost race, so propagate the real error (e.g. the DB CHECK on a 0-day policy).
      if (update(kind, maxAgeDays) == 0) {
        throw insertFailed;
      }
    }
  }
}
