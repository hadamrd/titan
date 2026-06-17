package io.adaptiq.titan.scm.bitbucket;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared Jackson {@link ObjectMapper} for parsing payloads received <em>from</em> Bitbucket Cloud
 * (REST list/create comment responses). Mirrors {@code GithubJson}: tolerant of fields Bitbucket
 * adds later — {@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES} is OFF (issue #1117).
 *
 * <p>Unlike GitHub, Bitbucket Cloud's JSON is already mostly snake_case but nested under {@code
 * content.raw} / {@code inline.to}; we read those with explicit {@code path()} traversal rather
 * than a naming strategy, so no global naming strategy is applied here.
 */
public final class BitbucketJson {

  /** The shared, configured mapper. Thread-safe per Jackson's contract. */
  public static final ObjectMapper MAPPER = build();

  private BitbucketJson() {}

  private static ObjectMapper build() {
    ObjectMapper m = new ObjectMapper();
    m.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    return m;
  }
}
