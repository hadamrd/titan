package io.adaptiq.titan.api.triggers.bitbucket;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.trigger.BitbucketTrigger;

/**
 * The discriminated-union of Bitbucket Cloud webhook event kinds Titan accepts (issue #1079).
 *
 * <p>The wire-side discriminator is the {@code X-Event-Key} HTTP header (e.g. {@code repo:push},
 * {@code pullrequest:created}, {@code pullrequest:updated}). The constitution's typed-config rule
 * applies: consumers branch on this enum, never on URL / payload shape.
 *
 * <p>Both {@code pullrequest:created} and {@code pullrequest:updated} fire a build (on the PR head
 * branch); every other key — including {@code pullrequest:approved}, {@code pullrequest:fulfilled},
 * {@code repo:fork} — is {@link #UNKNOWN} and acknowledged with a {@code 200} no-op.
 */
public enum BitbucketEvent {
  REPO_PUSH(BitbucketTrigger.EVENT_REPO_PUSH, BitbucketTrigger.CFG_PUSH),
  PR_CREATED(BitbucketTrigger.EVENT_PR_CREATED, BitbucketTrigger.CFG_PULL_REQUEST),
  PR_UPDATED(BitbucketTrigger.EVENT_PR_UPDATED, BitbucketTrigger.CFG_PULL_REQUEST),
  /**
   * Sentinel for unknown / unsupported event kinds — handled with a 200 no-op for forward-compat.
   */
  UNKNOWN("", "");

  private final String eventKey;
  private final String configName;

  BitbucketEvent(@NonNull String eventKey, @NonNull String configName) {
    this.eventKey = eventKey;
    this.configName = configName;
  }

  /** The canonical {@code X-Event-Key} header value. */
  @NonNull
  public String eventKey() {
    return eventKey;
  }

  /** The internal config alias as it appears in {@code triggers[].bitbucket.events}. */
  @NonNull
  public String configName() {
    return configName;
  }

  /** Short identifier used in logs and stored trigger metadata. */
  @NonNull
  public String shortName() {
    return configName.isEmpty() ? "unknown" : configName;
  }

  /** Resolve an event kind from the {@code X-Event-Key} header. */
  @NonNull
  public static BitbucketEvent fromEventKey(@Nullable String key) {
    if (key == null) {
      return UNKNOWN;
    }
    String k = key.trim();
    for (BitbucketEvent e : values()) {
      if (e != UNKNOWN && e.eventKey.equalsIgnoreCase(k)) {
        return e;
      }
    }
    return UNKNOWN;
  }
}
