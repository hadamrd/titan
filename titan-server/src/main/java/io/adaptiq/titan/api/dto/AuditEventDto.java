package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.adaptiq.titan.store.rows.AuditLogRow;
import java.time.Instant;

/**
 * Wire shape for a single audit event (closes #478).
 *
 * <p>The {@code action} and {@code targetType} fields are string codes (e.g. {@code "JOB_CREATE"},
 * {@code "BUILD"}) so the UI can render them as a discriminated union without binding to a
 * server-side enum class. {@code detailsJson} is opaque JSON the UI parses per-action.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditEventDto(
    long id,
    Instant occurredAt,
    String actor,
    String action,
    String targetType,
    String targetId,
    String detailsJson) {

  public static AuditEventDto from(AuditLogRow row) {
    return new AuditEventDto(
        row.id,
        row.occurredAt,
        row.actor,
        row.action,
        row.targetType,
        row.targetId,
        row.detailsJson);
  }
}
