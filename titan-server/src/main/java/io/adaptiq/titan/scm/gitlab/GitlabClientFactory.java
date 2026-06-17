package io.adaptiq.titan.scm.gitlab;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Boundary interface for outbound calls to a GitLab REST API (issue #1134).
 *
 * <p>Per Manifesto §"External I/O": every external boundary lives behind a typed interface with a
 * Fake-for-tests. The reconcile loop never touches {@code java.net.http.HttpClient} directly — it
 * goes through this interface so the unit tests inject a deterministic in-memory fake and the
 * integration test injects a WireMock-pointed real impl.
 *
 * <p>The default production impl ({@link DefaultGitlabClientFactory}) is a thin wrapper around
 * {@link java.net.http.HttpClient} that:
 *
 * <ul>
 *   <li>Authenticates with a {@code PRIVATE-TOKEN} header from the configured personal-access /
 *       project-access token.
 *   <li>Sends {@code If-None-Match} when the caller passes a non-null etag, so an idle tick that
 *       returns {@code 304 Not Modified} costs near-zero quota (acceptance criterion: "respects
 *       GitLab API rate limits and uses {@code If-None-Match} where the GitLab REST API supports
 *       them").
 *   <li>Translates {@code 401} / {@code 4xx} / {@code 5xx} / timeouts into typed {@link
 *       GitlabApiException}s — never bare {@code RuntimeException}s.
 * </ul>
 */
public interface GitlabClientFactory {

  /**
   * Return events on {@code projectId} strictly newer than {@code sinceEventId} (or the GitLab
   * default retention window when {@code sinceEventId} is {@code null}), ascending by occurredAt.
   *
   * <p>{@code ifNoneMatch} carries the last-seen ETag for the events feed. When the server replies
   * {@code 304 Not Modified} the impl MUST return an empty list and the {@link PageResult#etag}
   * MUST be preserved unchanged — the caller keeps the same ETag for the next tick.
   *
   * @throws GitlabApiException for any non-2xx response, transport-level I/O failure, or parse
   *     failure. The reconcile loop catches this and applies per-repo backoff.
   */
  @NonNull
  PageResult listProjectEvents(
      @NonNull String projectId,
      @Nullable String sinceEventId,
      @Nullable String ifNoneMatch,
      int limit)
      throws GitlabApiException;

  /**
   * Page of GitLab events plus the ETag the server returned, which the caller persists and feeds
   * back on the next tick to short-circuit unchanged feeds.
   */
  final class PageResult {
    private final List<GitlabEventDto> events;
    private final String etag;
    private final boolean notModified;

    public PageResult(@NonNull List<GitlabEventDto> events, @Nullable String etag) {
      this(events, etag, false);
    }

    public PageResult(
        @NonNull List<GitlabEventDto> events, @Nullable String etag, boolean notModified) {
      this.events = List.copyOf(events);
      this.etag = etag;
      this.notModified = notModified;
    }

    @NonNull
    public List<GitlabEventDto> events() {
      return events;
    }

    @Nullable
    public String etag() {
      return etag;
    }

    /** True iff the server responded {@code 304 Not Modified} — events list is empty. */
    public boolean notModified() {
      return notModified;
    }
  }
}
