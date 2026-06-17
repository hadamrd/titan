package io.adaptiq.titan.queue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.util.Map;

/**
 * Single dispatch unit for one {@code rf_task_queue} message type. Each handler owns one action
 * ({@code ADVANCE}, {@code BAKE}, {@code SYNTHESIZE}, {@code REPLAY_FROM_NODE}) and is responsible
 * for the build-side write + task-row close/fail.
 *
 * <p>Extracted per design 67 step 6 — the host {@link QueueProcessor} now only owns the
 * claim/dispatch loop and routes by action to the matching handler.
 */
interface QueueMessageHandler {
  /**
   * @param daos engine stores supplied by the current {@link QueueProcessor#tick}.
   * @param task the claimed row this handler must close.
   * @param payload pre-parsed JSON payload (already validated as non-null shape by the dispatcher).
   */
  void handle(
      @NonNull TitanStores daos, @NonNull TaskQueueRow task, @NonNull Map<String, Object> payload);
}
