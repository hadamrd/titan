package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Provider-agnostic envelope for a single SCM event surfaced by the reconcile loop (issue #1118).
 *
 * <p>The reconcile loop never touches provider-specific payload shapes; it only needs the
 * (provider, eventId, occurredAt) triple for dedupe + cursor advance, plus the original raw body
 * and event-type header so it can hand the event back to the SAME dispatch path that handles live
 * webhook deliveries — guaranteeing that a recovered event behaves identically to a hot- path one
 * (no parallel handler, per acceptance criteria).
 *
 * <p>Discriminated-union note: {@code provider} is bound by {@link ScmProvider}. The dedupe row's
 * primary key uses the enum's lowercase {@code name()} as its DB string form — never the raw
 * provider string from a header.
 */
public final class ScmEvent {

  private final ScmProvider provider;
  private final String repoExternalId;
  private final String eventId;
  private final String eventType;
  private final Instant occurredAt;
  private final byte[] rawBody;

  public ScmEvent(
      @NonNull ScmProvider provider,
      @NonNull String repoExternalId,
      @NonNull String eventId,
      @NonNull String eventType,
      @NonNull Instant occurredAt,
      @Nullable byte[] rawBody) {
    if (repoExternalId.isEmpty()) {
      throw new IllegalArgumentException("repoExternalId must not be empty");
    }
    if (eventId.isEmpty()) {
      throw new IllegalArgumentException("eventId must not be empty");
    }
    if (eventType.isEmpty()) {
      throw new IllegalArgumentException("eventType must not be empty");
    }
    this.provider = provider;
    this.repoExternalId = repoExternalId;
    this.eventId = eventId;
    this.eventType = eventType;
    this.occurredAt = occurredAt;
    // Defensive copy so caller mutation can't break dedupe semantics post-construction.
    this.rawBody = rawBody == null ? new byte[0] : rawBody.clone();
  }

  @NonNull
  public ScmProvider provider() {
    return provider;
  }

  @NonNull
  public String repoExternalId() {
    return repoExternalId;
  }

  @NonNull
  public String eventId() {
    return eventId;
  }

  @NonNull
  public String eventType() {
    return eventType;
  }

  @NonNull
  public Instant occurredAt() {
    return occurredAt;
  }

  @NonNull
  public byte[] rawBody() {
    return rawBody.clone();
  }
}
