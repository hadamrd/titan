package io.adaptiq.titan.trigger.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.db.TitanDataException;
import io.adaptiq.titan.store.TaskQueueDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobTriggerRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

/**
 * Db-backed {@link TriggerScope} — the engine's view of one job's trigger state, bound to the
 * transactional connection that {@link DbTriggerStore} locked (design/50, Tier 3).
 *
 * <p>Every operation runs on {@code conn}, the connection holding the {@code FOR UPDATE} lock on
 * the {@code titan.jobs} row: the last-fired read, the coalescing check, the build insert and the
 * state update all commit as one atomic unit (design/50 D4). The instance is valid only for the
 * duration of the {@code inLockedScope} callback.
 */
final class DbTriggerScope implements TriggerScope {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final Connection conn;
  private final TitanStores stores;
  private final long jobId;

  DbTriggerScope(@NonNull Connection conn, @NonNull TitanStores stores, long jobId) {
    this.conn = conn;
    this.stores = stores;
    this.jobId = jobId;
  }

  @Override
  @CheckForNull
  public Instant lastFiredAt(@NonNull String triggerId) {
    return stores.jobTriggers().find(conn, jobId, triggerId).map(r -> r.lastFiredAt).orElse(null);
  }

  @Override
  public boolean buildInFlight() {
    return stores.builds().hasNonTerminalBuild(conn, jobId);
  }

  @Override
  public void fire() {
    int buildNumber = stores.builds().nextBuildNumber(conn, jobId);
    BuildRow build = new BuildRow();
    build.jobId = jobId;
    build.buildNumber = buildNumber;
    build.status = "QUEUED";
    build.queuedAt = Instant.now();
    build.triggeredBy = "trigger";
    build.triggerType = "TitanTimerCause";
    long buildId = stores.builds().insert(conn, build);

    TaskQueueRow task = new TaskQueueRow();
    task.type = "ORCHESTRATE";
    task.queueName = "default";
    task.status = "QUEUED";
    task.priority = 5;
    task.attempts = 0;
    task.maxAttempts = 3;
    task.visibilityTimeoutSeconds = 300;
    task.buildId = buildId;
    task.taskToken = UUID.randomUUID();
    task.availableAt = Instant.now();
    task.createdAt = Instant.now();
    try {
      ObjectNode payload = MAPPER.createObjectNode();
      payload.put("buildId", buildId);
      task.payloadJson = MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      throw new TitanDataException("Failed to serialize trigger task payload", e);
    }
    TitanStores.onConnection(conn, TaskQueueDao.class, dao -> dao.insert(task));
  }

  @Override
  public void recordFired(@NonNull String triggerId, @NonNull Instant when) {
    stores.jobTriggers().recordState(conn, jobId, triggerId, when, null);
  }

  @Override
  public void recordError(@NonNull String triggerId, @NonNull String message) {
    JobTriggerRow existing = stores.jobTriggers().find(conn, jobId, triggerId).orElse(null);
    Instant lastFired = existing != null ? existing.lastFiredAt : null;
    stores.jobTriggers().recordState(conn, jobId, triggerId, lastFired, message);
  }
}
