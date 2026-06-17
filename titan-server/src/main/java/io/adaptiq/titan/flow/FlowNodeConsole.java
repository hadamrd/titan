package io.adaptiq.titan.flow;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.LogRow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Per-node console assembly — the server-side source of a node's console lines.
 *
 * <p>One DAG node's console lines, drawn from {@code titan.logs} keyed by the node's {@code
 * task_queue}/{@code task_archive} token(s). Each line is returned <em>without</em> its trailing
 * newline; callers re-terminate. Design/45 §5 rendering: when the node ended {@code FAILED} its
 * section ends with its structured {@code failure_reason} as a {@code ✗ <reason>} line — for a
 * {@code STEP_EXIT} node it follows the step's own log; for a {@code CREDENTIAL}/{@code DISPATCH}
 * node (which produced no step log) it <em>is</em> the node's only output.
 */
public final class FlowNodeConsole {

  private static final Logger LOGGER = Logger.getLogger(FlowNodeConsole.class.getName());

  private FlowNodeConsole() {}

  /**
   * Assemble a node's console lines from the given {@link TitanStores}. Best-effort: a missing
   * node, or one with no log chunks, returns an empty list (or just the failure-reason line).
   *
   * @param stores the data-access entry point (its task-queue + logs + flow-nodes DAOs are read)
   * @param buildId the build that owns the node
   * @param nodeId the DAG node id (e.g. {@code "build-s0"})
   * @return the node's per-line console, possibly empty
   */
  @NonNull
  public static List<String> nodeLogLines(
      @NonNull TitanStores stores, long buildId, @NonNull String nodeId) {
    List<String> lines = new ArrayList<>();
    try {
      for (UUID token : stores.taskQueue().logTokensForNode(buildId, nodeId)) {
        for (LogRow log : stores.logs().listByTask(token)) {
          if (log.data != null) {
            Collections.addAll(lines, log.data.split("\n", -1));
          }
        }
      }
      appendFailureReason(stores, lines, buildId, nodeId);
    } catch (RuntimeException e) {
      LOGGER.log(Level.WARNING, "[titan] node-log assembly failed for node {0}", nodeId);
    }
    return lines;
  }

  /**
   * Append a {@code FAILED} node's structured {@code failure_reason} (design/45) as a closing
   * {@code ✗ <reason>} line. A missing node, or one with no recorded reason, adds nothing.
   */
  private static void appendFailureReason(
      @NonNull TitanStores stores,
      @NonNull List<String> lines,
      long buildId,
      @NonNull String nodeId) {
    FlowNodeRow node = stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElse(null);
    if (node != null
        && "FAILED".equals(node.status)
        && node.failureReason != null
        && !node.failureReason.isBlank()) {
      lines.add("✗ " + node.failureReason);
    }
  }
}
