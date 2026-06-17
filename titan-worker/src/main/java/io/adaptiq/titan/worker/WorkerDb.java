package io.adaptiq.titan.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * All database access for the worker — JDBC against the {@code titan} schema, backed by a small
 * HikariCP connection pool.
 *
 * <p>With {@code TITAN_EXECUTORS > 1} the worker runs several tasks at once, so a fresh {@code
 * DriverManager} connect handshake per call (the old design) is a per-operation tax that does not
 * scale. Every method borrows a connection for the duration of one short statement and returns it
 * immediately — a task never holds a connection while its subprocess runs — so the pool is sized
 * for concurrent in-flight DB <em>operations</em>, not for the executor count, and a small fixed
 * cap serves a worker with any number of executors (see {@link #poolSize(WorkerConfig)}). The class
 * is trivially thread-safe (HikariCP is thread-safe and the poll loop, the heartbeat thread, and N
 * task threads all call in concurrently).
 */
final class WorkerDb implements AutoCloseable {

  private final HikariDataSource pool;

  WorkerDb(WorkerConfig cfg) {
    HikariConfig hc = new HikariConfig();
    hc.setJdbcUrl(cfg.jdbcUrl());
    hc.setUsername(cfg.dbUser());
    hc.setPassword(cfg.dbPassword());
    hc.setMaximumPoolSize(poolSize(cfg));
    // The pool floor — keep the heartbeat + poll threads' connections warm
    // even when no task is running, so liveness never pays a connect cost.
    hc.setMinimumIdle(Math.min(2, poolSize(cfg)));
    hc.setPoolName("titan-worker-db");
    // Each method runs a self-contained unit of work; autoCommit on by
    // default. claim() flips it off for the duration of its own borrow and
    // HikariCP resets it to this default when the connection is returned.
    hc.setAutoCommit(true);
    // Fail fast rather than block the poll loop forever if the DB is down.
    hc.setConnectionTimeout(30_000);
    this.pool = new HikariDataSource(hc);
  }

  /** Connection-pool ceiling — see {@link #poolSize(WorkerConfig)}. */
  static final int MAX_POOL_SIZE = 16;

  /**
   * Maximum pool size for a worker running {@code numExecutors} tasks at once.
   *
   * <p>It is <em>not</em> one connection per task. Every {@code WorkerDb} method borrows a
   * connection only for one short statement (claim, markProcessing, an appendLog chunk, complete)
   * and returns it at once; a task holds no connection while its subprocess runs — the long part.
   * So the pool only has to cover the connections in use <em>at one instant</em>, which is bounded
   * by concurrent in-flight DB operations, not by the executor count. A small fixed cap therefore
   * serves a worker with any number of executors.
   *
   * <p>This matters at scale: a 100-executor worker sized {@code numExecutors + 2} would open a
   * 102-connection pool, and a fleet of such workers would blow past the database's {@code
   * max_connections}. The cap prevents that. {@code numExecutors + 2} (the {@code + 2} = the poll
   * and heartbeat threads, which {@code minimumIdle} keeps warm so a busy worker never starves the
   * heartbeat — design/26 Tier C, design/30) is kept for small workers, but capped at {@value
   * #MAX_POOL_SIZE}.
   */
  static int poolSize(WorkerConfig cfg) {
    return Math.min(Math.max(1, cfg.numExecutors()) + 2, MAX_POOL_SIZE);
  }

  private Connection open() throws SQLException {
    return pool.getConnection();
  }

  /** Close the connection pool — called on worker shutdown. */
  @Override
  public void close() {
    pool.close();
  }

  /**
   * A task this worker has claimed and now owns (until the lease expires).
   *
   * <p>{@code traceParent} carries the W3C trace-context string the controller stamped onto {@code
   * task_queue.trace_parent} at enqueue (issue #314, PR #382). The worker uses it to continue the
   * originating trace — see {@link WorkerTracing#startTaskSpan}. {@code null} when the row had no
   * traceparent (server enqueued from a non-traced context, or row predates V17).
   */
  record ClaimedTask(
      long id,
      UUID taskToken,
      UUID claimToken,
      String type,
      String payloadJson,
      String traceParent) {}

  /**
   * Register (or refresh) this agent. Stamps {@code last_heartbeat=now()} so the agent is
   * considered online immediately, with no gap before the first heartbeat (doc-27 G1).
   * Update-then-insert — portable, no {@code ON CONFLICT}.
   */
  void register(WorkerConfig cfg) throws SQLException {
    try (Connection c = open()) {
      try (PreparedStatement up =
          c.prepareStatement(
              "UPDATE titan.agents SET display_name=?, labels=?, num_executors=?, "
                  + "remote_fs=?, usage_mode=?, status='ONLINE', "
                  + "registered_at=CURRENT_TIMESTAMP, "
                  + "last_heartbeat=CURRENT_TIMESTAMP, os_info=?, java_version=?, "
                  + "capabilities_json=? "
                  + "WHERE agent_id=?")) {
        up.setString(1, cfg.displayName());
        up.setString(2, cfg.labels());
        up.setInt(3, cfg.numExecutors());
        up.setString(4, cfg.remoteFs());
        up.setString(5, cfg.usageMode());
        up.setString(6, osInfo());
        up.setString(7, System.getProperty("java.version"));
        up.setString(8, capabilitiesJson());
        up.setString(9, cfg.agentId());
        if (up.executeUpdate() == 0) {
          insert(c, cfg);
        }
      }
    }
  }

  private static void insert(Connection c, WorkerConfig cfg) throws SQLException {
    try (PreparedStatement ins =
        c.prepareStatement(
            "INSERT INTO titan.agents (agent_id, display_name, labels, num_executors, "
                + "remote_fs, usage_mode, status, registered_at, last_heartbeat, "
                + "os_info, java_version, capabilities_json) "
                + "VALUES (?, ?, ?, ?, ?, ?, 'ONLINE', CURRENT_TIMESTAMP, "
                + "CURRENT_TIMESTAMP, ?, ?, ?)")) {
      ins.setString(1, cfg.agentId());
      ins.setString(2, cfg.displayName());
      ins.setString(3, cfg.labels());
      ins.setInt(4, cfg.numExecutors());
      ins.setString(5, cfg.remoteFs());
      ins.setString(6, cfg.usageMode());
      ins.setString(7, osInfo());
      ins.setString(8, System.getProperty("java.version"));
      ins.setString(9, capabilitiesJson());
      ins.executeUpdate();
    }
  }

  /** OS name + CPU architecture, e.g. {@code "Linux (amd64)"} — reported into {@code os_info}. */
  private static String osInfo() {
    return System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")";
  }

  /**
   * Selected JVM/host system properties, as a JSON object, reported into {@code capabilities_json}.
   * The controller serves these as the worker's "system properties" so channel-based node monitors
   * (e.g. the Architecture column) have data to show despite there being no Remoting channel.
   */
  private static String capabilitiesJson() {
    ObjectNode caps = JSON.createObjectNode();
    for (String key :
        new String[] {
          "os.name", "os.arch", "os.version",
          "java.version", "java.vm.name", "java.runtime.version",
          "user.name", "user.timezone"
        }) {
      String v = System.getProperty(key);
      if (v != null) {
        caps.put(key, v);
      }
    }
    caps.put("available.processors", Runtime.getRuntime().availableProcessors());
    return caps.toString();
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Record a liveness heartbeat, stamping the latest CPU/MEM/DISK sample alongside (#348). Any of
   * {@code cpuPct} / {@code memPct} / {@code diskPct} may be null when the worker could not sample
   * that reading — the column is written as SQL NULL in that case, and the UI renders an em-dash.
   */
  void heartbeat(String agentId, Integer cpuPct, Integer memPct, Integer diskPct)
      throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.agents SET last_heartbeat=CURRENT_TIMESTAMP, "
                    + "cpu_percent=?, memory_percent=?, disk_percent=? "
                    + "WHERE agent_id=?")) {
      setNullableInt(ps, 1, cpuPct);
      setNullableInt(ps, 2, memPct);
      setNullableInt(ps, 3, diskPct);
      ps.setString(4, agentId);
      ps.executeUpdate();
    }
  }

  private static void setNullableInt(PreparedStatement ps, int idx, Integer value)
      throws SQLException {
    if (value == null) {
      ps.setNull(idx, java.sql.Types.INTEGER);
    } else {
      ps.setInt(idx, value);
    }
  }

  /** Mark this agent OFFLINE — called on graceful shutdown. */
  void markOffline(String agentId) throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement("UPDATE titan.agents SET status='OFFLINE' WHERE agent_id=?")) {
      ps.setString(1, agentId);
      ps.executeUpdate();
    }
  }

  /**
   * Atomically claim one {@code EXECUTE_COMMAND} task from {@code queueName}, writing a fresh
   * {@code claim_token} lease. Uses {@code SELECT ... FOR UPDATE SKIP LOCKED} so concurrent workers
   * never claim the same row and never block each other.
   *
   * @return the claimed task, or empty if nothing is claimable.
   */
  Optional<ClaimedTask> claim(String agentId, String queueName) throws SQLException {
    UUID claimToken = UUID.randomUUID();
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        long id;
        try (PreparedStatement sel =
            c.prepareStatement(
                "SELECT id FROM titan.task_queue WHERE status='QUEUED' "
                    + "AND type='EXECUTE_COMMAND' AND queue_name=? "
                    + "AND available_at <= CURRENT_TIMESTAMP "
                    + "ORDER BY priority DESC, created_at "
                    + "LIMIT 1 FOR UPDATE SKIP LOCKED")) {
          sel.setString(1, queueName);
          try (ResultSet rs = sel.executeQuery()) {
            if (!rs.next()) {
              c.rollback();
              return Optional.empty();
            }
            id = rs.getLong(1);
          }
        }
        try (PreparedStatement upd =
            c.prepareStatement(
                "UPDATE titan.task_queue SET status='CLAIMED', claim_token=?, "
                    + "claimed_by=?, claimed_at=CURRENT_TIMESTAMP, attempts=attempts+1 "
                    + "WHERE id=?")) {
          upd.setObject(1, claimToken);
          upd.setString(2, agentId);
          upd.setLong(3, id);
          upd.executeUpdate();
        }
        ClaimedTask task;
        try (PreparedStatement get =
            c.prepareStatement(
                "SELECT task_token, type, payload_json, trace_parent "
                    + "FROM titan.task_queue WHERE id=?")) {
          get.setLong(1, id);
          try (ResultSet rs = get.executeQuery()) {
            rs.next();
            task =
                new ClaimedTask(
                    id,
                    rs.getObject("task_token", UUID.class),
                    claimToken,
                    rs.getString("type"),
                    rs.getString("payload_json"),
                    rs.getString("trace_parent"));
          }
        }
        c.commit();
        return Optional.of(task);
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  /**
   * Write a build's synthesised {@code pipeline_model_json} (design/38 Stage 1b — worker-side
   * synthesis). Retry-safe by construction at the caller: the {@code SynthesisTaskHandler} writes
   * only when the column is still empty, so a re-delivered SYNTHESIZE task does not overwrite an
   * already-synthesised model. The {@code WHERE pipeline_model_json IS NULL} guard makes the write
   * itself a no-op if two workers race the same task.
   *
   * @return {@code true} if this call wrote the model, {@code false} if it was already set.
   */
  boolean writePipelineModelJson(long buildId, String modelJson) throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.builds SET pipeline_model_json=? "
                    + "WHERE id=? AND pipeline_model_json IS NULL")) {
      ps.setString(1, modelJson);
      ps.setLong(2, buildId);
      return ps.executeUpdate() == 1;
    }
  }

  /**
   * The status of a build, or {@code null} if no such build row exists. Used by the {@link
   * WorkspaceReaper} to decide whether a {@code build-<id>} workspace is finished with (design/43
   * W4).
   */
  String buildStatus(long buildId) throws SQLException {
    try (Connection c = open();
        PreparedStatement ps = c.prepareStatement("SELECT status FROM titan.builds WHERE id=?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getString(1) : null;
      }
    }
  }

  /** True if a build already has a synthesised {@code pipeline_model_json}. */
  boolean hasPipelineModel(long buildId) throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement("SELECT pipeline_model_json FROM titan.builds WHERE id=?")) {
      ps.setLong(1, buildId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return false;
        }
        String json = rs.getString(1);
        return json != null && !json.isBlank();
      }
    }
  }

  /**
   * Whether the worker should stop executing this task — true if the row has been moved to
   * CANCELLED (the legacy abort/timeout path) <em>or</em> if the controller has stamped a
   * cancel-intent signal on it (#668, the proper in-flight signal that does not race the worker's
   * own claim-token-guarded completion).
   *
   * <p>Honouring both flags is load-bearing: the abort path now stamps {@code cancel_requested_at}
   * <em>before</em> flipping {@code status='CANCELLED'}, so a heartbeat tick that lands between the
   * two writes still gets a "yes, stop" answer. A future cancel-just-the-step API can stamp the
   * intent without touching status and this poll still works.
   */
  boolean isCancelled(long taskId) throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT status, cancel_requested_at FROM titan.task_queue WHERE id = ?")) {
      ps.setLong(1, taskId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          // Row no longer in the live queue — task already terminal and archived. Returning
          // false matches the prior behaviour: the worker's token-guarded complete() will
          // simply no-op against the missing row, and a healthy short step is never killed by
          // a race with the per-tick archive sweep.
          return false;
        }
        return "CANCELLED".equals(rs.getString(1)) || rs.getTimestamp(2) != null;
      }
    }
  }

  /** Transition a claimed task to PROCESSING — the worker has started it. */
  void markProcessing(long taskId, UUID claimToken) throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.task_queue SET status='PROCESSING' "
                    + "WHERE id=? AND claim_token=? AND status='CLAIMED'")) {
      ps.setLong(1, taskId);
      ps.setObject(2, claimToken);
      ps.executeUpdate();
    }
  }

  /** Append one log chunk for a task. */
  void appendLog(UUID taskToken, int chunkIndex, String stream, String data, boolean isFinal)
      throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.logs (task_id, chunk_index, stream, data, is_final) "
                    + "VALUES (?, ?, ?, ?, ?)")) {
      ps.setObject(1, taskToken);
      ps.setInt(2, chunkIndex);
      ps.setString(3, stream);
      ps.setString(4, data);
      ps.setBoolean(5, isFinal);
      ps.executeUpdate();
    }
  }

  /**
   * Record (or refresh) one archived artifact / stash in {@code titan.artifact} (design/41 32E-2).
   * The bytes are already in the {@code ArtifactStore}; this writes only the metadata row that
   * locates them. {@code ON CONFLICT (build_id, kind, name)} makes a re-archive — a re-run of the
   * producing step — overwrite the row in place, so the step stays idempotent (design/32 §4);
   * {@code created_at} is refreshed so it reflects the live copy.
   */
  void upsertArtifact(
      long buildId,
      String nodeId,
      String kind,
      String name,
      long sizeBytes,
      String sha256,
      String storage,
      String storageRef)
      throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO titan.artifact "
                    + "(build_id, node_id, kind, name, size_bytes, sha256, "
                    + " storage, storage_ref) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (build_id, kind, name) DO UPDATE SET "
                    + "node_id=EXCLUDED.node_id, size_bytes=EXCLUDED.size_bytes, "
                    + "sha256=EXCLUDED.sha256, storage=EXCLUDED.storage, "
                    + "storage_ref=EXCLUDED.storage_ref, "
                    + "created_at=CURRENT_TIMESTAMP")) {
      ps.setLong(1, buildId);
      ps.setString(2, nodeId);
      ps.setString(3, kind);
      ps.setString(4, name);
      ps.setLong(5, sizeBytes);
      ps.setString(6, sha256);
      ps.setString(7, storage);
      ps.setString(8, storageRef);
      ps.executeUpdate();
    }
  }

  /**
   * Insert a batch of parsed JUnit test-case rows for one build into {@code titan.test_result}
   * (issue #298). One short transaction, one prepared statement, one round trip per batch.
   * Idempotency for the {@code junit} step is convergence-by-replace: every call first clears the
   * build's rows on this node, then re-inserts — a re-parse of the same XMLs converges on the same
   * set rather than accumulating duplicates.
   *
   * <p>An empty batch is a no-op (the {@code allowEmptyResults: true} path on a step that matched
   * no XMLs ends here so the prior submission, if any, is still cleared).
   */
  void insertTestResults(long buildId, String nodeId, List<TestCaseRow> rows) throws SQLException {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        // Clear any prior rows for this (build, node) — a re-run of the same junit step on the
        // same flow node converges on the new XMLs. Other nodes' rows on the same build are
        // untouched: a build with parallel branches each running their own junit step keeps the
        // branches independent.
        try (PreparedStatement del =
            c.prepareStatement(
                "DELETE FROM titan.test_result WHERE build_id = ? AND node_id = ?")) {
          del.setLong(1, buildId);
          del.setString(2, nodeId);
          del.executeUpdate();
        }
        if (!rows.isEmpty()) {
          try (PreparedStatement ins =
              c.prepareStatement(
                  "INSERT INTO titan.test_result "
                      + "(build_id, node_id, suite, class_name, name, status, "
                      + " duration_ms, failure_message) "
                      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (TestCaseRow r : rows) {
              ins.setLong(1, buildId);
              ins.setString(2, nodeId);
              ins.setString(3, r.suite());
              ins.setString(4, r.className());
              ins.setString(5, r.name());
              ins.setString(6, r.status());
              ins.setLong(7, r.durationMs());
              if (r.failureMessage() == null) {
                ins.setNull(8, java.sql.Types.VARCHAR);
              } else {
                ins.setString(8, r.failureMessage());
              }
              ins.addBatch();
            }
            ins.executeBatch();
          }
        }
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  /**
   * Wire-shape for one test-case row written by {@link #insertTestResults}. Mirrors {@link
   * io.adaptiq.titan.worker.step.TestResultSink.Case} but lives package-locally so {@link
   * DbTestResultSink} translates SPI → JDBC without exposing JDBC to the SPI.
   */
  record TestCaseRow(
      String suite,
      String className,
      String name,
      String status,
      long durationMs,
      String failureMessage) {}

  /**
   * Record a {@code PRODUCED} fingerprint for a content hash (design/41 §8.3) — in one transaction:
   * the canonical {@code titan.fingerprint} row ({@code ON CONFLICT DO NOTHING}, so the first build
   * to produce the content keeps the {@code first_build_id}), then a {@code titan.fingerprint_ref}
   * edge ({@code ON CONFLICT DO NOTHING}, so a re-run does not double the edge). Both conflict
   * guards make the call idempotent.
   */
  void recordFingerprint(String hash, String fileName, long buildId, String nodeId)
      throws SQLException {
    try (Connection c = open()) {
      c.setAutoCommit(false);
      try {
        try (PreparedStatement fp =
            c.prepareStatement(
                "INSERT INTO titan.fingerprint (hash, file_name, first_build_id) "
                    + "VALUES (?, ?, ?) ON CONFLICT (hash) DO NOTHING")) {
          fp.setString(1, hash);
          fp.setString(2, fileName);
          fp.setLong(3, buildId);
          fp.executeUpdate();
        }
        try (PreparedStatement ref =
            c.prepareStatement(
                "INSERT INTO titan.fingerprint_ref (hash, build_id, node_id, role) "
                    + "VALUES (?, ?, ?, 'PRODUCED') "
                    + "ON CONFLICT (hash, build_id, role) DO NOTHING")) {
          ref.setString(1, hash);
          ref.setLong(2, buildId);
          ref.setString(3, nodeId);
          ref.executeUpdate();
        }
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
    }
  }

  /**
   * Terminally complete a task — but only if the supplied {@code claimToken} still matches the
   * row's lease. A zombie worker whose task was reaped and re-claimed by a peer holds a stale token
   * and is rejected (doc-27 G3).
   *
   * <p>The completion also <strong>scrubs the sealed credential bundle</strong> from the row:
   * {@code payload_json - 'credentialsSealed'} drops the AES-256-GCM ciphertext (design/39 §3.1)
   * the instant the task is done — credential material does not outlive its task's scope, even as
   * encrypted bytes. The {@code -} operator is a no-op on a payload that never had the field, so an
   * ordinary task is unaffected.
   *
   * @return {@code true} if the completion was accepted, {@code false} if rejected as stale.
   */
  boolean complete(long taskId, UUID claimToken, String status, String resultJson)
      throws SQLException {
    try (Connection c = open();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE titan.task_queue SET status=?, result_json=?, "
                    + "completed_at=CURRENT_TIMESTAMP, "
                    + "payload_json=(payload_json::jsonb - 'credentialsSealed')::text "
                    + "WHERE id=? AND claim_token=? AND status IN ('CLAIMED','PROCESSING')")) {
      ps.setString(1, status);
      ps.setString(2, resultJson);
      ps.setLong(3, taskId);
      ps.setObject(4, claimToken);
      return ps.executeUpdate() == 1;
    }
  }
}
