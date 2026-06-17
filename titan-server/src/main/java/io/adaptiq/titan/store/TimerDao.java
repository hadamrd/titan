package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.TimerRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

/**
 * DAO for {@code titan.timers} — the durable-timer substrate (timer subsystem, Phase 1).
 *
 * <p>The claim is the portable two-step pattern shared with {@code DiscoveryEventDao}: an {@code
 * UPDATE … WHERE id IN (SELECT … LIMIT)} flips due {@code ARMED} rows to {@code CLAIMED} under a
 * fresh token, then a {@code SELECT} by that token returns exactly the claimed rows. No {@code FOR
 * UPDATE SKIP LOCKED} — runs identically on H2 (PostgreSQL mode) and PostgreSQL.
 */
@RegisterFieldMapper(TimerRow.class)
public interface TimerDao {

  String COLS =
      "id, build_id, node_id, kind, fire_at, status, payload_json, "
          + "claim_token, claimed_at, created_at, fired_at";

  @SqlQuery("SELECT " + COLS + " FROM titan.timers WHERE id = :id")
  @NonNull
  Optional<TimerRow> findById(@Bind("id") long id);

  @SqlQuery("SELECT " + COLS + " FROM titan.timers WHERE build_id = :buildId ORDER BY id")
  @NonNull
  List<TimerRow> listByBuild(@Bind("buildId") long buildId);

  @SqlQuery(
      "SELECT " + COLS + " FROM titan.timers WHERE claim_token = :claimToken ORDER BY fire_at")
  @NonNull
  List<TimerRow> selectClaimed(@Bind("claimToken") @NonNull String claimToken);

  /**
   * Arm a timer, idempotent on {@code (buildId,nodeId,kind)}: inserts an {@code ARMED} row only if
   * no {@code ARMED} or {@code CLAIMED} timer already exists for that triple. Returns 1 if a row
   * was inserted, 0 if an active timer already covered it.
   */
  @SqlUpdate(
      "INSERT INTO titan.timers (build_id, node_id, kind, fire_at, status, payload_json) "
          + "SELECT :buildId, :nodeId, :kind, :fireAt, 'ARMED', :payloadJson "
          + "WHERE NOT EXISTS (SELECT 1 FROM titan.timers "
          + "WHERE build_id = :buildId AND node_id = :nodeId AND kind = :kind "
          + "AND status IN ('ARMED','CLAIMED'))")
  int armIfAbsent(
      @Bind("buildId") long buildId,
      @Bind("nodeId") @NonNull String nodeId,
      @Bind("kind") @NonNull String kind,
      @Bind("fireAt") @NonNull Instant fireAt,
      @Bind("payloadJson") @Nullable String payloadJson);

  /** Claim up to {@code limit} due {@code ARMED} timers, flipping them to {@code CLAIMED}. */
  @Transaction
  @NonNull
  default List<TimerRow> claimDue(int limit) {
    String token = UUID.randomUUID().toString();
    if (markDueAsClaimed(token, limit) == 0) {
      return List.of();
    }
    return selectClaimed(token);
  }

  @SqlUpdate(
      "UPDATE titan.timers "
          + "SET status = 'CLAIMED', claim_token = :claimToken, claimed_at = CURRENT_TIMESTAMP "
          + "WHERE status = 'ARMED' AND id IN (SELECT id FROM titan.timers "
          + "WHERE status = 'ARMED' AND fire_at <= CURRENT_TIMESTAMP "
          + "ORDER BY fire_at LIMIT :limit)")
  int markDueAsClaimed(@Bind("claimToken") @NonNull String claimToken, @Bind("limit") int limit);

  /** Return a stale {@code CLAIMED} timer (a sweep that died) to {@code ARMED}. */
  @SqlUpdate(
      "UPDATE titan.timers SET status = 'ARMED', claim_token = NULL, claimed_at = NULL "
          + "WHERE status = 'CLAIMED' AND claimed_at < :cutoff")
  int reclaimStale(@Bind("cutoff") @NonNull Instant cutoff);

  /** Transition a CLAIMED timer to FIRED. Returns 1 on success, 0 if the row was not CLAIMED. */
  @SqlUpdate(
      "UPDATE titan.timers "
          + "SET status = 'FIRED', fired_at = CURRENT_TIMESTAMP, claim_token = NULL, claimed_at = NULL "
          + "WHERE id = :id AND status = 'CLAIMED'")
  int markFired(@Bind("id") long id);

  @SqlUpdate(
      "UPDATE titan.timers SET status = 'CANCELLED' "
          + "WHERE build_id = :buildId AND node_id = :nodeId AND kind = :kind AND status = 'ARMED'")
  int cancel(
      @Bind("buildId") long buildId,
      @Bind("nodeId") @NonNull String nodeId,
      @Bind("kind") @NonNull String kind);

  @SqlUpdate(
      "UPDATE titan.timers SET status = 'CANCELLED' "
          + "WHERE build_id = :buildId AND status = 'ARMED'")
  int cancelAll(@Bind("buildId") long buildId);
}
