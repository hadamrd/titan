package io.adaptiq.titan.api.triggers.gitlab;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.trigger.GitlabTrigger;

/**
 * The discriminated-union of GitLab webhook event kinds Titan accepts (issue #1078).
 *
 * <p>The wire-side discriminator is the {@code X-Gitlab-Event} HTTP header (e.g. {@code Push Hook},
 * {@code Merge Request Hook}, {@code Tag Push Hook}); the body-side discriminator is the {@code
 * object_kind} JSON field. Both forms are accepted as input — the constitution's typed- config rule
 * applies: consumers branch on this enum, never on URL / payload shape.
 */
public enum GitlabEvent {
  PUSH(GitlabTrigger.EVENT_PUSH, "push", GitlabTrigger.CFG_PUSH),
  MERGE_REQUEST(
      GitlabTrigger.EVENT_MERGE_REQUEST, "merge_request", GitlabTrigger.CFG_MERGE_REQUEST),
  TAG_PUSH(GitlabTrigger.EVENT_TAG_PUSH, "tag_push", GitlabTrigger.CFG_TAG_PUSH),
  /**
   * Sentinel for unknown / unsupported event kinds — handled with a 200 no-op for forward-compat.
   */
  UNKNOWN("", "", "");

  private final String header;
  private final String objectKind;
  private final String configName;

  GitlabEvent(@NonNull String header, @NonNull String objectKind, @NonNull String configName) {
    this.header = header;
    this.objectKind = objectKind;
    this.configName = configName;
  }

  /** The canonical {@code X-Gitlab-Event} header value. */
  @NonNull
  public String headerName() {
    return header;
  }

  /** The canonical {@code object_kind} body value. */
  @NonNull
  public String objectKind() {
    return objectKind;
  }

  /** The internal config alias as it appears in {@code triggers[].gitlab.events}. */
  @NonNull
  public String configName() {
    return configName;
  }

  /** Short identifier used in logs and stored trigger metadata. */
  @NonNull
  public String shortName() {
    return configName.isEmpty() ? "unknown" : configName;
  }

  /**
   * Whether this event MUST carry an {@code object_kind} field in the body. {@code true} for every
   * known kind — only {@link #UNKNOWN} is exempt (we don't enforce a contract on payloads we will
   * ignore anyway).
   */
  public boolean requiresObjectKind() {
    return this != UNKNOWN;
  }

  /**
   * Resolve an event kind from the {@code X-Gitlab-Event} header, falling back to the {@code
   * object_kind} field when the header is missing or unknown.
   */
  @NonNull
  public static GitlabEvent fromHeaderOrKind(@Nullable String header, @Nullable String objectKind) {
    GitlabEvent fromHeader = fromHeader(header);
    if (fromHeader != UNKNOWN) {
      return fromHeader;
    }
    return fromObjectKind(objectKind);
  }

  @NonNull
  private static GitlabEvent fromHeader(@Nullable String header) {
    if (header == null) {
      return UNKNOWN;
    }
    String h = header.trim();
    for (GitlabEvent e : values()) {
      if (e == UNKNOWN) {
        continue;
      }
      if (e.header.equalsIgnoreCase(h)) {
        return e;
      }
    }
    return UNKNOWN;
  }

  @NonNull
  private static GitlabEvent fromObjectKind(@Nullable String kind) {
    if (kind == null) {
      return UNKNOWN;
    }
    String k = kind.trim();
    for (GitlabEvent e : values()) {
      if (e == UNKNOWN) {
        continue;
      }
      if (e.objectKind.equalsIgnoreCase(k)) {
        return e;
      }
    }
    return UNKNOWN;
  }
}
