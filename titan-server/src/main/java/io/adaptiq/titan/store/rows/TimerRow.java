package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * One row of {@code titan.timers} — a durable, controller-native scheduled wake-up.
 *
 * <p>Lifecycle: {@code ARMED → CLAIMED} (a sweep picked it up) {@code → FIRED}. A {@code CLAIMED}
 * row whose sweep died is reclaimed to {@code ARMED}. {@code cancel} flips {@code ARMED →
 * CANCELLED}. Mutable public-field POJO, mapped by JDBI's field mapper — no getters, no
 * constructor.
 */
public class TimerRow {

  public long id;
  public long buildId;
  public String nodeId;
  public String kind; // SLEEP | TIMEOUT | RETRY_BACKOFF | GATE_RESUME
  public Instant fireAt;
  public String status; // ARMED | CLAIMED | FIRED | CANCELLED

  @Nullable public String payloadJson;

  @Nullable public String claimToken;

  @Nullable public Instant claimedAt;

  public Instant createdAt;

  @Nullable public Instant firedAt;
}
