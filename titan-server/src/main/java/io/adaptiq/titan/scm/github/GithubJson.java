package io.adaptiq.titan.scm.github;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Shared Jackson {@link ObjectMapper} for parsing payloads received <em>from</em> GitHub
 * (manifest-callback response, REST responses, webhook bodies). Configured to be tolerant of fields
 * GitHub adds later — {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} is OFF, so an
 * unrecognised property is silently skipped rather than throwing. Closes #873.
 *
 * <p>Snake-case naming is the GitHub convention ({@code client_id}, {@code html_url}, {@code
 * suspended_at}); the mapper applies {@link PropertyNamingStrategies#SNAKE_CASE} so Java DTOs can
 * stay camelCase. {@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES} stays ON so a missing
 * required field surfaces as a clear error rather than silently defaulting to 0/false.
 */
public final class GithubJson {

  /** The shared, configured mapper. Thread-safe per Jackson's contract. */
  public static final ObjectMapper MAPPER = build();

  private GithubJson() {}

  private static ObjectMapper build() {
    ObjectMapper m = new ObjectMapper();
    m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    m.configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, true);
    m.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    return m;
  }
}
