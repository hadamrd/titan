package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.discovery_events} — one observed pipeline file at
 * one content revision. Deduped on {@code (sourceId, repo, commitSha)}: a re-poll of an unchanged
 * file produces no new row.
 */
public class DiscoveryEventRow {
  public long id;

  public long sourceId;

  public String repo;

  /** Branch the file was seen on. Null → branch not applicable (e.g. local directory). */
  @Nullable public String branch;

  /** Content revision — blob SHA (GitHub) or content hash (local dir). Null → unversioned. */
  @Nullable public String commitSha;

  public String eventType;

  /** Opaque JSON payload captured at scan time. Null → none. */
  @Nullable public String payloadJson;

  /** Lifecycle: {@code pending} → {@code processing} → {@code done}/{@code failed}. */
  public String status;

  public Instant receivedAt;

  /** When the event reached a terminal state. Null → not yet processed. */
  @Nullable public Instant processedAt;

  /** Human-readable processing outcome. Null → not yet processed. */
  @Nullable public String outcomeMessage;

  /** Times this event has been processed; incremented on every transition into {@code failed}. */
  public int attemptCount;

  /** UUID of the worker pass that currently owns this row. Null → not claimed. */
  @Nullable public String claimToken;

  /** When the row was moved to {@code processing}. Null → not claimed. Drives the stale sweep. */
  @Nullable public Instant claimedAt;
}
