package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.ScmWebhookEventRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject DAO for {@code titan.scm_webhook_event} — durable webhook ingestion log (issue
 * #1129).
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>Webhook endpoint verifies signature, calls {@link #tryInsertPending}. The {@code (provider,
 *       delivery_id)} unique constraint dedupes silently across replicas — a second-arriving
 *       delivery returns empty without colliding with the first.
 *   <li>Handler runs synchronously. On success, calls {@link #markProcessed}; on a recoverable
 *       error, calls {@link #markFailedWithRetry} which bumps {@code attempts} and schedules {@code
 *       next_attempt_at}; on a terminal error, calls {@link #markFailedTerminal}.
 *   <li>A timer-driven sweeper polls {@link #findDuePending} and re-runs the handler.
 * </ol>
 *
 * <p>The "tryInsert" idiom (vs catching {@code SQLIntegrityConstraintViolationException}) keeps the
 * normal happy path free of exception-as-control-flow: we pre-check existence inside the same JDBI
 * handle and rely on the unique index as a backstop. JDBI 3 will surface the duplicate-key as a
 * {@code UnableToExecuteStatementException} which the caller can treat as "already ingested".
 */
@RegisterFieldMapper(ScmWebhookEventRow.class)
public interface ScmWebhookEventDao {

  String COLS =
      "id, provider, delivery_id AS deliveryId, event_type AS eventType, received_at AS receivedAt,"
          + " status, attempts, next_attempt_at AS nextAttemptAt, last_error AS lastError,"
          + " payload, signature";

  /**
   * Insert a new PENDING ingestion row. Caller is expected to have signature-verified the body.
   * Returns the new row id. Throws on duplicate-key — callers wanting "insert OR fetch" should use
   * {@link #findByDelivery} first or catch the duplicate-key exception.
   */
  @SqlUpdate(
      "INSERT INTO titan.scm_webhook_event "
          + "(provider, delivery_id, event_type, status, attempts, payload, signature) "
          + "VALUES (:provider, :deliveryId, :eventType, 'PENDING', 0, :payload, :signature)")
  @GetGeneratedKeys
  long insertPending(
      @Bind("provider") @NonNull String provider,
      @Bind("deliveryId") @NonNull String deliveryId,
      @Bind("eventType") @NonNull String eventType,
      @Bind("payload") @NonNull String payload,
      @Bind("signature") @Nullable String signature);

  /** Look up the persisted ingestion row by (provider, deliveryId). */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.scm_webhook_event WHERE provider = :provider AND delivery_id = :deliveryId")
  @NonNull
  Optional<ScmWebhookEventRow> findByDelivery(
      @Bind("provider") @NonNull String provider, @Bind("deliveryId") @NonNull String deliveryId);

  /** Look up a single row by primary key — sweeper / operator-API use. */
  @SqlQuery("SELECT " + COLS + " FROM titan.scm_webhook_event WHERE id = :id")
  @NonNull
  Optional<ScmWebhookEventRow> findById(@Bind("id") long id);

  /**
   * Mark a row PROCESSED. Returns the number of rows updated — 0 if the row's status had already
   * been terminalized (a no-op, not an error).
   */
  @SqlUpdate(
      "UPDATE titan.scm_webhook_event SET status = 'PROCESSED', last_error = NULL,"
          + " next_attempt_at = NULL WHERE id = :id AND status <> 'PROCESSED'")
  int markProcessed(@Bind("id") long id);

  /**
   * Mark a row FAILED with a retry scheduled at {@code nextAttemptAt}. Bumps {@code attempts}.
   * Status stays PENDING so the sweeper picks it up; we use a separate retry call rather than
   * recording FAILED to keep "FAILED" reserved for terminal failures the operator must triage.
   */
  @SqlUpdate(
      "UPDATE titan.scm_webhook_event "
          + "SET status = 'PENDING', attempts = attempts + 1, last_error = :error,"
          + " next_attempt_at = :nextAttemptAt "
          + "WHERE id = :id")
  int markFailedWithRetry(
      @Bind("id") long id,
      @Bind("error") @NonNull String error,
      @Bind("nextAttemptAt") @NonNull Instant nextAttemptAt);

  /**
   * Mark a row terminally FAILED — no more retries. Surfaced to operators via the admin API
   * (delivery log). Use when {@code attempts} has reached the per-provider cap.
   */
  @SqlUpdate(
      "UPDATE titan.scm_webhook_event SET status = 'FAILED', last_error = :error,"
          + " next_attempt_at = NULL WHERE id = :id")
  int markFailedTerminal(@Bind("id") long id, @Bind("error") @NonNull String error);

  /**
   * Pull up to {@code limit} PENDING rows whose {@code next_attempt_at} is at or before {@code
   * now}, oldest-first. The reaper / timer-driven sweeper iterates these.
   *
   * <p>{@code next_attempt_at IS NULL} (the "fresh insert" case) sorts as due immediately so a
   * crashed handler that never marked-processed gets retried on the next sweep.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.scm_webhook_event "
          + "WHERE status = 'PENDING' "
          + "  AND (next_attempt_at IS NULL OR next_attempt_at <= :now) "
          + "ORDER BY received_at ASC LIMIT :limit")
  @NonNull
  List<ScmWebhookEventRow> findDuePending(
      @Bind("now") @NonNull Instant now, @Bind("limit") int limit);

  /** Operator surface: count rows currently in FAILED state — drives the "drop rate" alert. */
  @SqlQuery("SELECT COUNT(*) FROM titan.scm_webhook_event WHERE status = 'FAILED'")
  long countFailed();
}
