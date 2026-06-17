package io.adaptiq.titan.scm.bitbucket;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin typed HTTP seam over the Bitbucket Cloud REST API (issue #1117). One concrete class; tests
 * inject a real {@link HttpClient} pointed at an in-process server and a no-op {@link Sleeper}.
 *
 * <p>Responsibilities:
 *
 * <ul>
 *   <li>Attach the {@code Authorization} header from a {@link BitbucketAuthProvider} (the only
 *       place a secret materialises on the wire).
 *   <li>Return every HTTP response — including 4xx/5xx — as a {@link Response} so callers decide
 *       graceful degradation. Only transport failures (I/O, interrupt) throw {@link
 *       BitbucketApiException}.
 *   <li>Honour a single {@code 429 Retry-After} retry, capped at {@code maxRetryAfterMillis} so a
 *       hostile/buggy header can never wedge the build thread.
 * </ul>
 *
 * <p>Stateless across calls — no mutable instance fields beyond the injected collaborators.
 */
public final class BitbucketClient {

  private static final Logger LOGGER = Logger.getLogger(BitbucketClient.class.getName());

  /** One Bitbucket REST response, status + body text. */
  public record Response(int status, @NonNull String body) {
    public boolean isSuccess() {
      return status >= 200 && status < 300;
    }
  }

  /** Internal raw response carrying the Retry-After header for the 429 path. */
  private record Raw(int status, @NonNull String body, @Nullable String retryAfter) {}

  /** Injectable sleep so tests don't actually block on a {@code Retry-After}. */
  @FunctionalInterface
  public interface Sleeper {
    void sleepMillis(long millis) throws InterruptedException;
  }

  private final HttpClient http;
  private final String baseUrl;
  private final Sleeper sleeper;
  private final long maxRetryAfterMillis;

  public BitbucketClient(
      @NonNull HttpClient http,
      @NonNull String baseUrl,
      @NonNull Sleeper sleeper,
      long maxRetryAfterMillis) {
    this.http = http;
    this.baseUrl = trimTrailingSlash(baseUrl);
    this.sleeper = sleeper;
    this.maxRetryAfterMillis = Math.max(0, maxRetryAfterMillis);
  }

  /**
   * Send one request. {@code path} is appended to the base URL verbatim (caller URL-encodes path
   * segments). A {@code null} body issues a body-less request for the given method.
   *
   * @throws BitbucketApiException only on transport failure (I/O / interrupt), never on an HTTP
   *     error status.
   */
  @NonNull
  public Response send(
      @NonNull String method,
      @NonNull String path,
      @NonNull BitbucketAuthProvider auth,
      @Nullable String jsonBody) {
    Raw first = doSend(method, path, auth, jsonBody);
    if (first.status() != 429) {
      return new Response(first.status(), first.body());
    }
    long waitMs =
        Math.min(maxRetryAfterMillis, parseRetryAfterMillis(first.retryAfter()).orElse(1000L));
    LOGGER.log(
        Level.FINE,
        "[bitbucket] 429 from {0} {1}; honouring Retry-After, sleeping {2}ms then retrying once",
        new Object[] {method, path, waitMs});
    try {
      sleeper.sleepMillis(waitMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BitbucketApiException("interrupted honouring Bitbucket 429 Retry-After", -1, e);
    }
    Raw second = doSend(method, path, auth, jsonBody);
    return new Response(second.status(), second.body());
  }

  @NonNull
  private Raw doSend(
      @NonNull String method,
      @NonNull String path,
      @NonNull BitbucketAuthProvider auth,
      @Nullable String jsonBody) {
    URI uri = URI.create(baseUrl + path);
    HttpRequest.BodyPublisher publisher =
        jsonBody == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(jsonBody);
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("Authorization", auth.authorizationHeaderValue())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .method(method, publisher)
            .build();
    try {
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      String body = resp.body() == null ? "" : resp.body();
      String retryAfter = resp.headers().firstValue("Retry-After").orElse(null);
      return new Raw(resp.statusCode(), body, retryAfter);
    } catch (IOException e) {
      throw new BitbucketApiException("Bitbucket I/O error on " + method + " " + path, -1, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BitbucketApiException("interrupted on " + method + " " + path, -1, e);
    }
  }

  @NonNull
  private static OptionalLong parseRetryAfterMillis(@Nullable String hdr) {
    if (hdr == null || hdr.isBlank()) {
      return OptionalLong.empty();
    }
    try {
      // Bitbucket sends delta-seconds (an integer). HTTP-date form is not used by Bitbucket Cloud.
      long seconds = Long.parseLong(hdr.trim());
      return OptionalLong.of(Math.max(0, seconds) * 1000L);
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
  }

  @NonNull
  private static String trimTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
