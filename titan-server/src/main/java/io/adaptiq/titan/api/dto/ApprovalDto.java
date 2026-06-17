package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.ApprovalService;
import io.adaptiq.titan.store.rows.ApprovalRow;
import java.time.Instant;
import java.util.List;

/**
 * Wire shape of one row in {@code titan.approvals} — what {@code GET /api/v1/approvals} and the
 * decide endpoints return.
 *
 * <p>{@code approvers} is the parsed JSON list (a wire concern — clients should not need to know
 * the column is a string). NULL-valued fields are omitted from the response.
 */
@JsonInclude(Include.NON_NULL)
public record ApprovalDto(
    long id,
    long buildId,
    @NonNull String flowNodeId,
    @NonNull String prompt,
    @NonNull List<String> approvers,
    @NonNull String status,
    @Nullable String decidedBy,
    @Nullable Instant decidedAt,
    @NonNull Instant expiresAt,
    @NonNull Instant createdAt) {

  /** Project a DAO row onto the wire shape. */
  public static ApprovalDto from(@NonNull ApprovalRow row) {
    return new ApprovalDto(
        row.id,
        row.buildId,
        row.flowNodeId,
        row.prompt,
        ApprovalService.parseApprovers(row.approversJson == null ? "[]" : row.approversJson),
        row.status,
        row.decidedBy,
        row.decidedAt,
        row.expiresAt,
        row.createdAt);
  }
}
