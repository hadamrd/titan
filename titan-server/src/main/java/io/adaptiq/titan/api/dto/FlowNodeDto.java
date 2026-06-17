package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.adaptiq.titan.flow.orch.OutputContext;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * API response DTO for a single flow node (DAG vertex). Returned by {@code GET
 * /api/v1/builds/{buildId}/nodes}.
 *
 * <p>{@code outputs} is the key/value bag a step published via {@code setOutput(k, v)} during
 * execution (design/29 §6 — the templating source for {@code ${{ steps[...].outputs.X }}}). The map
 * is extracted from the node's persisted {@code result_json} (the same data the orchestrator reads
 * via {@link OutputContext#outputsOf}), so the UI surfaces exactly what downstream steps can
 * template against — no schema change, no second round-trip. Always a (possibly empty) non-null
 * map; values are coerced to their JSON string form so the wire shape stays a flat {@code
 * Map<String,String>} regardless of whether the step published numbers, booleans, or nested JSON.
 *
 * <p><b>Secrets:</b> outputs are content the <em>step itself</em> chose to publish — credentials
 * resolved by {@code CredentialResolver} are injected into the worker's env and never round-trip
 * back into {@code result_json}. The worker's log-masker covers the per-task stdout stream; it does
 * <em>not</em> filter values a step explicitly hands to {@code setOutput}. A step that publishes a
 * secret-derived value is publishing it intentionally — the SRE looking at the Outputs panel needs
 * to see what downstream stages can template against, which is the same value those stages will
 * receive. (Issue #782 surfaces what the engine already exposes; a future tightening that masks
 * credentials in outputs too would live in the worker, not here.)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowNodeDto(
    long buildId,
    String nodeId,
    String parentIds,
    String nodeType,
    String displayName,
    String stepDescriptor,
    String status,
    String agentLabel,
    Instant startedAt,
    Instant completedAt,
    Long durationMs,
    int attempt,
    int maxAttempts,
    String failureCategory,
    String failureReason,
    UUID logTaskId,
    Map<String, String> outputs) {

  /** Map from a storage row. */
  public static FlowNodeDto from(FlowNodeRow row) {
    return new FlowNodeDto(
        row.buildId,
        row.nodeId,
        row.parentIds,
        row.nodeType,
        row.displayName,
        row.stepDescriptor,
        row.status,
        row.agentLabel,
        row.startedAt,
        row.completedAt,
        row.durationMs,
        row.attempt,
        row.maxAttempts,
        row.failureCategory,
        row.failureReason,
        row.logTaskId,
        outputsFromResultJson(row.resultJson));
  }

  /**
   * Flatten the {@code outputs} object out of a node's {@code result_json} into a wire-friendly
   * {@code Map<String, String>}. Reuses {@link OutputContext#outputsOf} so the API can never
   * disagree with what the orchestrator's templating engine actually resolves. Non-string values
   * (numbers, booleans, nested JSON) are stringified via {@code String.valueOf} — the wire shape
   * stays uniform and the UI's key/value table never has to type-switch.
   */
  private static Map<String, String> outputsFromResultJson(String resultJson) {
    Map<String, Object> raw = OutputContext.outputsOf(resultJson);
    if (raw.isEmpty()) {
      return Map.of();
    }
    Map<String, String> flat = new LinkedHashMap<>(raw.size());
    for (Map.Entry<String, Object> e : raw.entrySet()) {
      Object v = e.getValue();
      flat.put(e.getKey(), v == null ? "" : String.valueOf(v));
    }
    return flat;
  }
}
