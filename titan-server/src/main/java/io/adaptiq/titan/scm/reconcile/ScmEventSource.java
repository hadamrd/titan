package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Provider-side adapter: "give me events for repo {@code R} strictly newer than cursor {@code C}"
 * (issue #1118).
 *
 * <p>Implementations:
 *
 * <ul>
 *   <li>{@link io.adaptiq.titan.scm.github.GithubEventSource} — calls GitHub's {@code
 *       /repos/{owner}/{repo}/events} feed.
 *   <li>GitLab / Bitbucket — stub: {@link #supportsReconcile()} returns false and {@link
 *       #listEventsSince} throws {@link UnsupportedOperationException}. The scheduler checks the
 *       capability flag and silently skips unsupported providers.
 * </ul>
 *
 * <p>External-boundary rule (Manifesto §"External I/O"): every concrete impl ships with a
 * deterministic in-memory {@code Fake} for unit tests. See {@code FakeScmEventSource} in {@code
 * titan-server/src/test/...}.
 */
public interface ScmEventSource {

  /** The provider this source serves. */
  @NonNull
  ScmProvider provider();

  /**
   * Capability flag: {@code true} iff {@link #listEventsSince} is implemented. Lets the scheduler
   * skip providers we have not yet wired without a runtime exception per tick.
   */
  default boolean supportsReconcile() {
    return true;
  }

  /**
   * Return events for {@code repoExternalId} strictly newer than {@code sinceEventId} (or all
   * events in the configured retention window when {@code sinceEventId} is {@code null}). The
   * returned list MUST be ordered ascending by occurredAt; the scheduler advances the cursor to the
   * last id only after a successful dispatch.
   *
   * @throws ScmReconcileException for transport / API / parse failures — the scheduler catches and
   *     applies per-repo backoff; the loop does not crash.
   */
  @NonNull
  List<ScmEvent> listEventsSince(
      @NonNull String repoExternalId, @Nullable String sinceEventId, int batchCap)
      throws ScmReconcileException;
}
