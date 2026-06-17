package io.adaptiq.titan.trigger;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Map;

/**
 * An out-of-band event that asks the scheduler to evaluate now — the push counterpart of the 60 s
 * poll tick (design/51 D1).
 *
 * <p>Immutable. A {@code TriggerSource} (or an HTTP webhook endpoint) builds one and hands it to
 * {@code TriggerEngine.deliver}; it reaches a {@link Trigger} via {@link TriggerContext#event()}.
 *
 * @param kind the event family, e.g. {@code "webhook"} — a trigger matches on this first.
 * @param key the within-kind selector, e.g. a webhook token or a Kafka topic.
 * @param attributes free-form event detail (a branch name, a commit, …); never {@code null}.
 * @param instant when the event occurred.
 */
public record TriggerEvent(
    @NonNull String kind,
    @NonNull String key,
    @NonNull Map<String, String> attributes,
    @NonNull Instant instant) {

  /** The {@link #kind()} of an HTTP webhook event. */
  public static final String WEBHOOK = "webhook";

  public TriggerEvent {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    instant = instant == null ? Instant.now() : instant;
  }

  /** A webhook event keyed by its token. */
  @NonNull
  public static TriggerEvent webhook(
      @NonNull String token, @NonNull Map<String, String> attributes) {
    return new TriggerEvent(WEBHOOK, token, attributes, Instant.now());
  }

  /** One attribute, or {@code null} if absent. */
  @CheckForNull
  public String attribute(@NonNull String name) {
    return attributes.get(name);
  }
}
