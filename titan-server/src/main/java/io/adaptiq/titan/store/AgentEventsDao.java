package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject for {@code titan.agent_events} — worker join/leave lifecycle log feeding the Home
 * activity timeline (closes #714).
 *
 * <p>v1 records two event types — {@code JOINED} and {@code LEFT}. {@code HEARTBEAT_LOST} is
 * reserved in the migration's {@code CHECK} but not emitted: the stale-agent reaper that would
 * synthesise it does not yet exist; tracked as a follow-up.
 *
 * <p>Idempotency: {@link #recordJoined} dedupes a second {@code JOINED} for the same agent inside a
 * 5-minute window — re-registrations of a flapping worker would otherwise fan-out a noisy "joined"
 * row per heartbeat-tick, and the timeline should read as one logical join. The window is wired
 * through {@link #recordJoinedSince} so tests can inline-control it (a 0-second window forces an
 * unconditional insert).
 */
public interface AgentEventsDao {

  /** Default deduplication window for {@code JOINED} events (5 minutes). */
  int DEFAULT_JOINED_DEDUP_SECONDS = 300;

  /**
   * Idempotent {@code JOINED} insert with the production 5-minute deduplication window. Returns the
   * rows actually inserted (0 if suppressed as a duplicate, 1 if recorded).
   */
  default int recordJoined(@NonNull String agentId) {
    return recordJoinedSince(agentId, cutoff(DEFAULT_JOINED_DEDUP_SECONDS));
  }

  /**
   * INSERT-WHERE-NOT-EXISTS for {@code JOINED}: suppresses an insert if any {@code JOINED} for the
   * same {@code agent_id} exists with {@code occurred_at >= sinceCutoff}. Portable across H2 and
   * Postgres (no {@code ON CONFLICT}; no dialect-specific dedup). The cutoff is supplied as a bound
   * timestamp rather than computed in SQL so the dedup logic is testable from Java without forcing
   * a real-clock {@code Thread.sleep}.
   */
  @SqlUpdate(
      "INSERT INTO titan.agent_events (agent_id, event_type) "
          + "SELECT :agentId, 'JOINED' "
          + "WHERE NOT EXISTS ("
          + "  SELECT 1 FROM titan.agent_events "
          + "  WHERE agent_id = :agentId "
          + "    AND event_type = 'JOINED' "
          + "    AND occurred_at >= :sinceCutoff)")
  int recordJoinedSince(
      @Bind("agentId") @NonNull String agentId,
      @Bind("sinceCutoff") @NonNull java.sql.Timestamp sinceCutoff);

  /** Unconditional {@code LEFT} insert. Leaves are intentionally not deduplicated. */
  @SqlUpdate("INSERT INTO titan.agent_events (agent_id, event_type) VALUES (:agentId, 'LEFT')")
  void recordLeft(@Bind("agentId") @NonNull String agentId);

  /**
   * The {@code limit} most recent events newest-first, joined to {@code titan.agents} for {@code
   * display_name}. Used by {@code GET /api/v1/agents/events}.
   *
   * <p>An INNER JOIN is correct here because {@code agent_id} is a FK with {@code ON DELETE
   * CASCADE} — an event without a matching agent row cannot exist.
   */
  @SqlQuery(
      "SELECT e.id AS id, e.agent_id AS agent_id, "
          + "       COALESCE(a.display_name, e.agent_id) AS display_name, "
          + "       e.event_type AS event_type, e.occurred_at AS occurred_at "
          + "FROM titan.agent_events e JOIN titan.agents a ON a.agent_id = e.agent_id "
          + "ORDER BY e.occurred_at DESC, e.id DESC "
          + "LIMIT :limit")
  @RegisterConstructorMapper(AgentEventRow.class)
  @NonNull
  List<AgentEventRow> listRecent(@Bind("limit") int limit);

  /** Test helper: count all rows for an agent (any type). */
  @SqlQuery("SELECT COUNT(*) FROM titan.agent_events WHERE agent_id = :agentId")
  int countByAgent(@Bind("agentId") @NonNull String agentId);

  /** Test helper: count rows for an agent of a specific {@code event_type}. */
  @SqlQuery(
      "SELECT COUNT(*) FROM titan.agent_events "
          + "WHERE agent_id = :agentId AND event_type = :eventType")
  int countByAgentAndType(
      @Bind("agentId") @NonNull String agentId, @Bind("eventType") @NonNull String eventType);

  /** Wall-clock timestamp {@code seconds} in the past — the dedup-window cutoff. */
  private static java.sql.Timestamp cutoff(int seconds) {
    return new java.sql.Timestamp(System.currentTimeMillis() - seconds * 1000L);
  }

  /**
   * Row for {@link #listRecent}. Plain record with column-mapped constructor params so the API
   * layer can rewrap into its DTO without leaking JDBI types upstream.
   */
  record AgentEventRow(
      @ColumnName("id") long id,
      @ColumnName("agent_id") @NonNull String agentId,
      @ColumnName("display_name") @NonNull String displayName,
      @ColumnName("event_type") @NonNull String eventType,
      @ColumnName("occurred_at") @NonNull Instant occurredAt) {

    /** Nullable accessor convenience for forward-compat fields. */
    @Nullable
    public String displayNameOrNull() {
      return displayName;
    }
  }
}
