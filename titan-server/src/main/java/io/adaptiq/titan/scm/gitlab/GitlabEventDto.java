package io.adaptiq.titan.scm.gitlab;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * One row returned by the GitLab events endpoint, projected to the minimum set the reconcile loop
 * needs (issue #1134).
 *
 * <p>The raw GitLab event shape is bigger than this (target_type, author, push_data, …) but the
 * reconcile path only cares about (id, type, occurredAt, commit SHA, ref, rawJsonBody) — same
 * fields the live webhook handler reads. Provider-specific richness lives in {@code rawBody} so the
 * dispatcher can re-parse exactly as if a hot-path webhook had arrived.
 */
public final class GitlabEventDto {

  private final String eventId;
  private final String eventType;
  private final Instant occurredAt;
  private final String ref;
  private final String commitSha;
  private final byte[] rawBody;

  public GitlabEventDto(
      @NonNull String eventId,
      @NonNull String eventType,
      @NonNull Instant occurredAt,
      @Nullable String ref,
      @Nullable String commitSha,
      @Nullable byte[] rawBody) {
    if (eventId.isEmpty()) {
      throw new IllegalArgumentException("eventId must not be empty");
    }
    if (eventType.isEmpty()) {
      throw new IllegalArgumentException("eventType must not be empty");
    }
    this.eventId = eventId;
    this.eventType = eventType;
    this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    this.ref = ref;
    this.commitSha = commitSha;
    this.rawBody = rawBody == null ? new byte[0] : rawBody.clone();
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

  @Nullable
  public String ref() {
    return ref;
  }

  @Nullable
  public String commitSha() {
    return commitSha;
  }

  @NonNull
  public byte[] rawBody() {
    return rawBody.clone();
  }
}
