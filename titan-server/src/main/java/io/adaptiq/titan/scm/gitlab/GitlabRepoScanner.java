package io.adaptiq.titan.scm.gitlab;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.scm.reconcile.ScmEvent;
import io.adaptiq.titan.scm.reconcile.ScmEventSource;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.scm.reconcile.ScmReconcileException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GitLab-flavoured {@link ScmEventSource} that catches missed webhook events on a transient outage
 * (issue #1134).
 *
 * <p>The motivating bug: when a GitLab self-hosted instance is unreachable (flaky VPN, GitLab
 * restart, Titan restart, network blip) the push / MR webhook is delivered into a void and the
 * affected commits never build. GitLab does not retry past its short delivery window. This scanner
 * polls each registered project on a configurable interval (default 60s) and surfaces every event
 * the webhook handler missed — handing them back to the SAME dispatch tail the live webhook uses
 * (the {@link io.adaptiq.titan.scm.reconcile.WebhookDispatcher}), so no parallel build path.
 *
 * <h2>Dedupe contract</h2>
 *
 * <p>Race-safe against the live webhook: the shared {@link
 * io.adaptiq.titan.scm.reconcile.EventDedupeStore} guarantees exactly-one dispatch when reconcile
 * and webhook fire within milliseconds of each other for the same event. The scheduler's tick loop
 * calls {@link io.adaptiq.titan.scm.reconcile.EventDedupeStore#markSeen} before invoking the
 * dispatcher; the webhook handler does the same. Loser sees {@code markSeen → false} and skips.
 *
 * <h2>Rate-limit budget</h2>
 *
 * <p>An idle tick costs near-zero quota: we send the last-seen ETag back as {@code If-None-Match}
 * and short-circuit on {@code 304 Not Modified}. Per-project ETag is held in-memory across ticks
 * (see {@link #etagFor}). Auth (401/403) and 5xx failures are translated to {@link
 * ScmReconcileException}; the {@link io.adaptiq.titan.scm.reconcile.ReconcileScheduler} catches and
 * applies per-repo backoff (acceptance criterion: "failures do NOT crash the scheduler — the next
 * tick retries").
 */
public final class GitlabRepoScanner implements ScmEventSource {

  private static final Logger LOGGER = Logger.getLogger(GitlabRepoScanner.class.getName());

  private final GitlabClientFactory clientFactory;

  /** Per-project last-seen ETag — kept in-memory; an instance restart simply re-fetches once. */
  private final Map<String, String> etagByProject = new ConcurrentHashMap<>();

  public GitlabRepoScanner(@NonNull GitlabClientFactory clientFactory) {
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
  }

  @Override
  @NonNull
  public ScmProvider provider() {
    return ScmProvider.GITLAB;
  }

  @Override
  public boolean supportsReconcile() {
    return true;
  }

  @Override
  @NonNull
  public List<ScmEvent> listEventsSince(
      @NonNull String repoExternalId, @Nullable String sinceEventId, int batchCap)
      throws ScmReconcileException {
    String etag = etagByProject.get(repoExternalId);
    GitlabClientFactory.PageResult page;
    try {
      page = clientFactory.listProjectEvents(repoExternalId, sinceEventId, etag, batchCap);
    } catch (GitlabApiException e) {
      // Auth failures + transient 5xx all surface as ScmReconcileException. The reconcile loop
      // already logs project + status and applies per-repo backoff (Manifesto §"Errors": typed
      // boundary error, never bare RuntimeException).
      LOGGER.log(
          Level.WARNING,
          "[gitlab-reconcile] listProjectEvents failed for project {0}: HTTP {1}: {2}",
          new Object[] {repoExternalId, e.status(), e.getMessage()});
      throw new ScmReconcileException(
          ScmProvider.GITLAB,
          repoExternalId,
          "gitlab listProjectEvents failed: " + e.getMessage(),
          e);
    }

    // Refresh the cached ETag on every non-304 response so the next tick is a conditional GET.
    if (!page.notModified() && page.etag() != null) {
      etagByProject.put(repoExternalId, page.etag());
    }

    List<ScmEvent> out = new ArrayList<>(page.events().size());
    for (GitlabEventDto dto : page.events()) {
      out.add(
          new ScmEvent(
              ScmProvider.GITLAB,
              repoExternalId,
              dto.eventId(),
              dto.eventType(),
              dto.occurredAt(),
              dto.rawBody()));
    }
    return out;
  }

  /**
   * Test hook — exposes the in-memory ETag cache so unit tests can assert conditional-GET wiring.
   */
  @Nullable
  String etagFor(@NonNull String projectId) {
    return etagByProject.get(projectId);
  }
}
