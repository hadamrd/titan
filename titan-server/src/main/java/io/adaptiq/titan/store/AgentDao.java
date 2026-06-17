package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.AgentRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

/**
 * JDBI SqlObject access for {@code titan.agents}. Natural primary key: {@code agent_id}.
 *
 * <p>Liveness is heartbeat-based: an agent is "online" if its {@code last_heartbeat} is recent. The
 * controller never opens a Remoting channel — {@code isOnline()} is a {@code last_heartbeat} query
 * (doc-27). {@link #register} stamps {@code last_heartbeat} to {@code now()} so a
 * freshly-registered agent never reads stale before its first heartbeat (doc-27 G1).
 */
@RegisterFieldMapper(AgentRow.class)
public interface AgentDao {

  String COLS =
      "agent_id, display_name, labels, status, num_executors, max_concurrent, "
          + "current_tasks, os_info, java_version, capabilities_json, remote_fs, usage_mode, "
          + "last_heartbeat, registered_at, last_seen_by, endpoint_url, "
          + "cpu_percent, memory_percent, disk_percent";

  /** Lookup by agent_id. */
  @SqlQuery("SELECT " + COLS + " FROM titan.agents WHERE agent_id = :agentId")
  @NonNull
  Optional<AgentRow> findById(@Bind("agentId") @NonNull String agentId);

  /** All agents ordered by agent_id. */
  @SqlQuery("SELECT " + COLS + " FROM titan.agents ORDER BY agent_id")
  @NonNull
  List<AgentRow> listAll();

  /** Agents filtered by status, ordered by agent_id. */
  @SqlQuery("SELECT " + COLS + " FROM titan.agents WHERE status = :status ORDER BY agent_id")
  @NonNull
  List<AgentRow> listByStatus(@Bind("status") @NonNull String status);

  /**
   * Count of agents whose {@code status} matches — powers the {@code
   * titan_worker_count{status=...}} Prometheus gauge (#649). Avoids materialising rows for a gauge
   * sample.
   */
  @SqlQuery("SELECT COUNT(*) FROM titan.agents WHERE status = :status")
  int countByStatus(@Bind("status") @NonNull String status);

  /**
   * Create-or-update keyed on {@code agent_id}. Tries UPDATE first; if 0 rows affected, INSERTs.
   * Runs in a single transaction so the update/insert pair is atomic.
   */
  @Transaction
  default void upsert(@NonNull AgentRow row) {
    if (updateExisting(row) == 0) {
      insert(row);
    }
  }

  @SqlUpdate(
      "UPDATE titan.agents SET display_name = :displayName, labels = :labels, "
          + "status = :status, num_executors = :numExecutors, max_concurrent = :maxConcurrent, "
          + "current_tasks = :currentTasks, os_info = :osInfo, java_version = :javaVersion, "
          + "capabilities_json = :capabilitiesJson, last_heartbeat = :lastHeartbeat, "
          + "last_seen_by = :lastSeenBy, endpoint_url = :endpointUrl WHERE agent_id = :agentId")
  int updateExisting(@BindFields AgentRow row);

  @SqlUpdate(
      "INSERT INTO titan.agents (agent_id, display_name, labels, status, num_executors, "
          + "max_concurrent, current_tasks, os_info, java_version, capabilities_json, "
          + "last_heartbeat, registered_at, last_seen_by, endpoint_url) "
          + "VALUES (:agentId, :displayName, :labels, :status, :numExecutors, :maxConcurrent, "
          + ":currentTasks, :osInfo, :javaVersion, :capabilitiesJson, :lastHeartbeat, "
          + ":registeredAt, :lastSeenBy, :endpointUrl)")
  void insert(@BindFields AgentRow row);

  /**
   * Register (or re-register) an agent. Upserts the agent row with {@code status='ONLINE'}, {@code
   * registered_at=now()} and — crucially — {@code last_heartbeat=now()} so the agent is immediately
   * considered alive (doc-27 G1: no offline window between register and first heartbeat). A
   * re-registration of an existing agent refreshes all of these.
   *
   * <p>Implemented as an update-then-insert pair inside one transaction (portable across H2 and
   * PostgreSQL — H2 has no {@code ON CONFLICT}). The update/insert race is closed by the
   * surrounding transaction plus the {@code agent_id} primary key.
   */
  @Transaction
  default void register(
      @NonNull String agentId, String displayName, String labels, int numExecutors) {
    if (registerUpdate(agentId, displayName, labels, numExecutors) == 0) {
      registerInsert(agentId, displayName, labels, numExecutors);
    }
  }

  @SqlUpdate(
      "UPDATE titan.agents SET display_name = :displayName, labels = :labels, "
          + "num_executors = :numExecutors, status = 'ONLINE', "
          + "registered_at = CURRENT_TIMESTAMP, last_heartbeat = CURRENT_TIMESTAMP "
          + "WHERE agent_id = :agentId")
  int registerUpdate(
      @Bind("agentId") @NonNull String agentId,
      @Bind("displayName") String displayName,
      @Bind("labels") String labels,
      @Bind("numExecutors") int numExecutors);

  @SqlUpdate(
      "INSERT INTO titan.agents (agent_id, display_name, labels, num_executors, status, "
          + "registered_at, last_heartbeat) "
          + "VALUES (:agentId, :displayName, :labels, :numExecutors, 'ONLINE', "
          + "CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
  void registerInsert(
      @Bind("agentId") @NonNull String agentId,
      @Bind("displayName") String displayName,
      @Bind("labels") String labels,
      @Bind("numExecutors") int numExecutors);

  /** Record a liveness heartbeat — stamp {@code last_heartbeat} to now(). Idempotent. */
  @SqlUpdate("UPDATE titan.agents SET last_heartbeat = CURRENT_TIMESTAMP WHERE agent_id = :agentId")
  void heartbeat(@Bind("agentId") @NonNull String agentId);

  /** Update heartbeat timestamp and current task count. */
  @SqlUpdate(
      "UPDATE titan.agents SET last_heartbeat = CURRENT_TIMESTAMP, "
          + "current_tasks = :currentTasks WHERE agent_id = :agentId")
  void updateHeartbeat(
      @Bind("agentId") @NonNull String agentId, @Bind("currentTasks") int currentTasks);

  /**
   * True if the agent exists and has heartbeated within the last {@code withinSeconds} seconds. The
   * liveness predicate behind {@code TitanComputer.isOnline()}.
   *
   * <p>The cutoff timestamp is computed in Java rather than as {@code CURRENT_TIMESTAMP - (:n *
   * INTERVAL '1' SECOND)}: that arithmetic is valid on PostgreSQL but H2 cannot infer the type of a
   * bound integer multiplied by an interval ({@code "UNKNOWN * INTERVAL SECOND"}). A bound
   * timestamp is portable across both. Containers in the rig share the host clock, so
   * controller-vs-DB skew is negligible against the 30s liveness window.
   */
  default boolean hasRecentHeartbeat(@NonNull String agentId, int withinSeconds) {
    return hasHeartbeatSince(agentId, cutoff(withinSeconds));
  }

  @SqlQuery(
      "SELECT COUNT(*) > 0 FROM titan.agents WHERE agent_id = :agentId "
          + "AND last_heartbeat IS NOT NULL AND last_heartbeat >= :cutoff")
  boolean hasHeartbeatSince(
      @Bind("agentId") @NonNull String agentId, @Bind("cutoff") @NonNull java.sql.Timestamp cutoff);

  /**
   * Agents currently considered online: {@code status='ONLINE'} and a heartbeat within {@code
   * withinSeconds}. Ordered by agent_id. See {@link #hasRecentHeartbeat} for why the cutoff is
   * Java-computed.
   */
  @NonNull
  default List<AgentRow> listOnline(int withinSeconds) {
    return listOnlineSince(cutoff(withinSeconds));
  }

  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.agents WHERE status = 'ONLINE' "
          + "AND last_heartbeat IS NOT NULL AND last_heartbeat >= :cutoff "
          + "ORDER BY agent_id")
  @NonNull
  List<AgentRow> listOnlineSince(@Bind("cutoff") @NonNull java.sql.Timestamp cutoff);

  /**
   * Stale agents: those marked {@code ONLINE} whose {@code last_heartbeat} is older than {@code
   * staleSeconds} (or NULL — crashed during startup before the first heartbeat). The reaper marks
   * these {@code OFFLINE} and re-queues their in-flight tasks.
   */
  @NonNull
  default List<AgentRow> findStale(int staleSeconds) {
    return findStaleBefore(cutoff(staleSeconds));
  }

  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.agents WHERE status = 'ONLINE' "
          + "AND (last_heartbeat IS NULL OR last_heartbeat < :cutoff) "
          + "ORDER BY agent_id")
  @NonNull
  List<AgentRow> findStaleBefore(@Bind("cutoff") @NonNull java.sql.Timestamp cutoff);

  /** Wall-clock timestamp {@code seconds} in the past — the liveness/staleness cutoff. */
  private static java.sql.Timestamp cutoff(int seconds) {
    return new java.sql.Timestamp(System.currentTimeMillis() - seconds * 1000L);
  }

  /** Mark an agent OFFLINE. Idempotent. */
  @SqlUpdate("UPDATE titan.agents SET status = 'OFFLINE' WHERE agent_id = :agentId")
  void markOffline(@Bind("agentId") @NonNull String agentId);

  /** Update agent status. */
  @SqlUpdate("UPDATE titan.agents SET status = :status WHERE agent_id = :agentId")
  void updateStatus(
      @Bind("agentId") @NonNull String agentId, @Bind("status") @NonNull String status);

  /**
   * Initiate worker drain (UI §5.3): flip {@code status} from {@code ONLINE} (or {@code BUSY}) to
   * {@code DRAINING}. The worker's polling loop reads this on each tick and stops claiming new
   * tasks; in-flight tasks finish normally. Returns rows affected — {@code 0} if the agent does not
   * exist or is already {@code DRAINING}/{@code OFFLINE}.
   */
  @SqlUpdate(
      "UPDATE titan.agents SET status = 'DRAINING' "
          + "WHERE agent_id = :agentId AND status IN ('ONLINE','BUSY')")
  int markDraining(@Bind("agentId") @NonNull String agentId);

  /**
   * Revert a {@code DRAINING} worker back to {@code ONLINE} — the undrain action (UI §5.3). Only
   * flips rows currently in {@code DRAINING}; idempotent on already-ONLINE agents (returns 0).
   */
  @SqlUpdate(
      "UPDATE titan.agents SET status = 'ONLINE' "
          + "WHERE agent_id = :agentId AND status = 'DRAINING'")
  int markOnline(@Bind("agentId") @NonNull String agentId);

  /** Hard delete by agent_id. Idempotent. */
  @SqlUpdate("DELETE FROM titan.agents WHERE agent_id = :agentId")
  void delete(@Bind("agentId") @NonNull String agentId);
}
