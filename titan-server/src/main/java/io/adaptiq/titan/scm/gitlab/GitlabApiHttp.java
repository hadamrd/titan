package io.adaptiq.titan.scm.gitlab;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Single shared transport for the GitLab merge-request reporters ({@link GitlabMrCommentReporter}
 * and {@link GitlabMrReviewReporter}) — issue #1168 review follow-up.
 *
 * <p>Before this class each reporter injected a raw {@link HttpClient} and hand-rolled its own
 * connect-timeout, request-timeout, {@code PRIVATE-TOKEN} header construction, {@code send()}
 * method, {@code redactPath()} and pagination. The settings duplicated across two independent
 * classes and would have diverged the moment a third GitLab reporter landed. Centralising them here
 * gives one place to evolve timeouts / headers / redaction and one well-tested pagination walk.
 *
 * <p>This mirrors the role {@link GitlabClientFactory} / {@link DefaultGitlabClientFactory} play
 * for the reconcile loop (issue #1134) — a typed boundary over {@code java.net.http} — but for the
 * write-side (notes / discussions) where the token is resolved fresh per call from {@code
 * CredentialsService} rather than baked in at boot. Keeping a separate, per-call-token transport
 * here avoids contorting the reconcile factory's per-project-token constructor model.
 *
 * <h2>Security</h2>
 *
 * The {@code PRIVATE-TOKEN} is only ever set as a request header — it is never logged, never placed
 * in a URI, and {@link #redactPath(URI)} strips the query string so a logged URL can never carry a
 * token-bearing parameter. The transport itself logs nothing; callers decide what (token-free)
 * context to log on a non-2xx response.
 */
@ApplicationScoped
public class GitlabApiHttp {

  /** TCP connect timeout — fail fast rather than hang the status-update transaction. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /** Per-request timeout (whole request/response). */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Safety cap on how many pages a single {@code list} walk follows. GitLab caps {@code per_page}
   * at 100, so 20 pages = up to 2000 rows — far beyond any realistic MR note count while still
   * bounding a pathological / hostile {@code X-Next-Page} loop.
   */
  static final int MAX_PAGES = 20;

  private final HttpClient http;

  @Inject
  public GitlabApiHttp() {
    this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  /** Visible-for-testing constructor accepting an explicit {@link HttpClient}. */
  GitlabApiHttp(@NonNull HttpClient http) {
    this.http = http;
  }

  /**
   * Issue a single GitLab API request authenticated with {@code token}. {@code jsonBody} is sent as
   * a UTF-8 JSON entity when non-null (and the {@code Content-Type} header set accordingly);
   * otherwise the request carries no body. Returns the raw response — the caller inspects the
   * status code and decides what to do; this method never throws on a non-2xx status and never
   * logs.
   */
  @NonNull
  HttpResponse<byte[]> send(
      @NonNull String method, @NonNull URI uri, @Nullable String jsonBody, @NonNull String token)
      throws IOException, InterruptedException {
    HttpRequest.BodyPublisher publisher =
        jsonBody == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8);
    HttpRequest.Builder b =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("PRIVATE-TOKEN", token)
            .header("Accept", "application/json")
            .method(method, publisher);
    if (jsonBody != null) {
      b.header("Content-Type", "application/json");
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  /**
   * Parse the GitLab {@code X-Next-Page} pagination header. GitLab returns the next page number
   * there (and an empty string on the last page). Returns {@code 0} when the header is absent,
   * blank, or not a positive integer — i.e. "there is no next page".
   */
  static int nextPage(@NonNull HttpResponse<?> resp) {
    String header = resp.headers().firstValue("X-Next-Page").orElse("");
    if (header.isBlank()) {
      return 0;
    }
    try {
      int n = Integer.parseInt(header.trim());
      return n > 0 ? n : 0;
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Strip the query string so a logged URL can never carry a token-bearing parameter. */
  @NonNull
  static String redactPath(@NonNull URI uri) {
    return uri.getPath();
  }
}
