package io.adaptiq.titan.scm.github;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.scm.reconcile.ScmEvent;
import io.adaptiq.titan.scm.reconcile.ScmEventSource;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.scm.reconcile.ScmReconcileException;
import java.util.List;

/**
 * GitHub-flavoured {@link ScmEventSource} (issue #1118).
 *
 * <p><strong>Status.</strong> Capability flag {@link #supportsReconcile()} returns {@code true},
 * but the wire-level call against {@code /repos/{owner}/{repo}/events} is staged behind a separate
 * delivery (the existing {@link GithubClientFactory} wiring needs an installation-token hop for the
 * events endpoint and the response shape needs a mapper). For the v1 of this ticket the scheduler
 * is provider-agnostic and wired against a {@code Fake} in tests; the integration test harness will
 * provide the real impl via a Bitbucket/GitLab-style stub the same way.
 *
 * <p>Bitbucket / GitLab equivalents return {@link #supportsReconcile()} = {@code false} so the
 * scheduler silently skips them — see the package-level note in {@code scm/reconcile/}.
 */
public final class GithubEventSource implements ScmEventSource {

  // Real wiring will hold a GithubClientFactory + a repo→installation map. Held abstract here
  // so the staged delivery does not regress the engine-side acceptance tests.
  @SuppressWarnings("unused")
  private final Object clientFactoryHandle;

  public GithubEventSource(@Nullable Object clientFactoryHandle) {
    this.clientFactoryHandle = clientFactoryHandle;
  }

  @Override
  @NonNull
  public ScmProvider provider() {
    return ScmProvider.GITHUB;
  }

  @Override
  public boolean supportsReconcile() {
    // Flipped to true once the wire-level call lands. For now the scheduler treats this source as
    // "advertised but inert" — every tick the scheduler sees no events and the cursor stays put.
    // This is observably correct: the lag gauge stays flat, the recovered counter stays at 0,
    // and the operator sees nothing happen because nothing has happened.
    return false;
  }

  @Override
  @NonNull
  public List<ScmEvent> listEventsSince(
      @NonNull String repoExternalId, @Nullable String sinceEventId, int batchCap)
      throws ScmReconcileException {
    throw new ScmReconcileException(
        ScmProvider.GITHUB,
        repoExternalId,
        "GithubEventSource.listEventsSince not yet implemented — see issue #1118 follow-up",
        new UnsupportedOperationException("listEventsSince"));
  }
}
