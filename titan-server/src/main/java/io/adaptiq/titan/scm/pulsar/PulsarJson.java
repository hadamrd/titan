package io.adaptiq.titan.scm.pulsar;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared Jackson {@link ObjectMapper} for parsing payloads received <em>from</em> a Pulsar node
 * (repo list, change list, ref list) — issue #1280. Mirrors {@link
 * io.adaptiq.titan.scm.github.GithubJson}.
 *
 * <p>{@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} is OFF so a field Pulsar adds later
 * is skipped rather than throwing. {@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} stays
 * ON so a missing required primitive surfaces as a clear parse error rather than silently
 * defaulting.
 */
public final class PulsarJson {

  /** The shared, configured mapper. Thread-safe per Jackson's contract. */
  public static final ObjectMapper MAPPER = build();

  private PulsarJson() {}

  private static ObjectMapper build() {
    ObjectMapper m = new ObjectMapper();
    m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    m.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    return m;
  }
}
