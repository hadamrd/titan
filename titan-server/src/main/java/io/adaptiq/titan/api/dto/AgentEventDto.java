package io.adaptiq.titan.api.dto;

import io.adaptiq.titan.store.AgentEventsDao;
import java.time.Instant;

/**
 * Wire DTO for {@code GET /api/v1/agents/events} — one worker lifecycle event (closes #714).
 *
 * <p>{@code type} is the discriminator: {@code "JOINED"} or {@code "LEFT"} in v1; {@code
 * HEARTBEAT_LOST} is reserved server-side but not yet emitted (no reaper). The UI's timeline
 * switches on this string, matching the "discriminated-union end-to-end" convention.
 *
 * <ul>
 *   <li>{@code id} — monotonic event id (also used as a stable React key).
 *   <li>{@code agentId} — natural agent key (the same {@code agent_id} the workers API exposes).
 *   <li>{@code agentName} — {@code display_name} from {@code titan.agents}, falls back to {@code
 *       agentId} when the agent never set a display name.
 *   <li>{@code type} — {@code "JOINED"} | {@code "LEFT"}.
 *   <li>{@code occurredAt} — ISO-8601 instant of the event.
 * </ul>
 */
public record AgentEventDto(
    long id, String agentId, String agentName, String type, Instant occurredAt) {

  /** Wrap a {@link AgentEventsDao.AgentEventRow} into the wire shape. */
  public static AgentEventDto from(AgentEventsDao.AgentEventRow row) {
    return new AgentEventDto(
        row.id(), row.agentId(), row.displayName(), row.eventType(), row.occurredAt());
  }
}
