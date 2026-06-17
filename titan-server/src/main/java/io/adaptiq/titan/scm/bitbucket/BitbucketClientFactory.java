package io.adaptiq.titan.scm.bitbucket;

import edu.umd.cs.findbugs.annotations.NonNull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.http.HttpClient;
import java.time.Duration;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds {@link BitbucketClient} instances bound to the configured Bitbucket Cloud base URL (issue
 * #1117). Mirrors {@code GithubClientFactory}: a single seam so reporters stay free of HTTP wiring
 * and tests can swap the endpoint to {@code http://localhost:<port>}.
 *
 * <p>One shared {@link HttpClient} is reused across all reporters (connection-pool friendly). The
 * 429 back-off cap defaults to 5s — long enough to ride out a real Bitbucket rate-limit blip, short
 * enough that a hostile header can never wedge the synchronous build-status thread.
 */
@ApplicationScoped
public class BitbucketClientFactory {

  /** Production Bitbucket Cloud REST endpoint. */
  public static final String DEFAULT_BASE_URL = "https://api.bitbucket.org";

  /** Cap on a honoured {@code Retry-After} so a 429 retry can never block the thread for long. */
  static final long MAX_RETRY_AFTER_MILLIS = 5_000L;

  private final String baseUrl;
  private final HttpClient http;
  private final long maxRetryAfterMillis;

  @Inject
  public BitbucketClientFactory(
      @ConfigProperty(name = "bitbucket.base-url", defaultValue = DEFAULT_BASE_URL)
          String baseUrl) {
    this(
        baseUrl,
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
        MAX_RETRY_AFTER_MILLIS);
  }

  /** Visible-for-testing — inject an explicit client + retry cap. */
  BitbucketClientFactory(
      @NonNull String baseUrl, @NonNull HttpClient http, long maxRetryAfterMillis) {
    this.baseUrl = baseUrl;
    this.http = http;
    this.maxRetryAfterMillis = maxRetryAfterMillis;
  }

  /** Build a client. The sleeper is real {@link Thread#sleep} in production. */
  @NonNull
  public BitbucketClient create() {
    return new BitbucketClient(http, baseUrl, Thread::sleep, maxRetryAfterMillis);
  }

  @NonNull
  public String baseUrl() {
    return baseUrl;
  }
}
