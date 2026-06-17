package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.AgentRow;
import java.time.Instant;
import java.util.List;

/**
 * API response DTO for a single worker (agent) row — the wire format for {@code GET
 * /api/v1/workers} (UI §5.3 Workers grid).
 *
 * <p>Never exposes {@link AgentRow} directly: that POJO carries internal-only columns ({@code
 * capabilitiesJson}, {@code remoteFs}, {@code usageMode}, {@code lastSeenBy}, {@code endpointUrl})
 * that have no place on the wire. The {@link #from(AgentRow)} mapper enumerates the fields we DO
 * publish — every new column on {@code titan.agents} requires an explicit decision before it leaks
 * to the API.
 *
 * <p>Worker live-resource metrics (CPU / MEM / DISK percentages) are not yet tracked in the DB —
 * the v3 UI surfaces these as live bars. The DTO carries them as nullable until a real metrics
 * source lands; the UI renders absent values as "no signal" rather than a fake number.
 *
 * <p>{@code labels} on the row is a comma-separated string; the DTO splits it into a clean array so
 * the UI does not need to do string surgery.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkerDto(
    String id,
    String name,
    String state,
    String pool,
    List<String> labels,
    int currentTasks,
    int maxConcurrent,
    @Nullable Integer cpuPct,
    @Nullable Integer memPct,
    @Nullable Integer diskPct,
    @Nullable Instant lastSeenAt,
    @Nullable Instant registeredAt) {

  /**
   * Map from a storage row. {@code name} falls back to {@code agentId} when {@code displayName} is
   * null. {@code pool} is derived from the first label (Titan does not yet model an explicit pool
   * column — labels are the routing target). CPU/MEM/DISK come back null until a metrics source is
   * wired (issue #304 / follow-up).
   */
  public static WorkerDto from(AgentRow row) {
    List<String> labels = splitLabels(row.labels);
    String pool = labels.isEmpty() ? "default" : labels.get(0);
    return new WorkerDto(
        row.agentId,
        row.displayName != null ? row.displayName : row.agentId,
        row.status,
        pool,
        labels,
        row.currentTasks,
        row.maxConcurrent,
        row.cpuPercent,
        row.memoryPercent,
        row.diskPercent,
        row.lastHeartbeat,
        row.registeredAt);
  }

  /** Split the comma-separated labels column into a clean list. Empty/null → empty list. */
  private static List<String> splitLabels(@Nullable String labels) {
    if (labels == null || labels.isBlank()) {
      return List.of();
    }
    return java.util.Arrays.stream(labels.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }
}
