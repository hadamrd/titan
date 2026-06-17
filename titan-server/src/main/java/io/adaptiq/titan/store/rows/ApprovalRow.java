package io.adaptiq.titan.store.rows;

import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.approvals} — one parked human-approval gate (#715).
 *
 * <p>Public fields per the project convention (JDBI {@code @RegisterFieldMapper}). {@code
 * approversJson} is the verbatim JSON array string stored in the column; parsing into a {@code
 * List<String>} is the service layer's job (mirror of {@code GateModel.approvers}).
 *
 * <p>Status is a closed enum on the wire: {@code PENDING}, {@code APPROVED}, {@code REJECTED},
 * {@code TIMED_OUT}. The DB CHECK constraint also enforces that {@code decidedBy} / {@code
 * decidedAt} are both set iff status leaves {@code PENDING}.
 */
public class ApprovalRow {
  public long id;
  public long buildId;
  public String flowNodeId;
  public String prompt;
  public String approversJson;
  public String status;
  public String decidedBy;
  public Instant decidedAt;
  public Instant expiresAt;
  public Instant createdAt;
}
