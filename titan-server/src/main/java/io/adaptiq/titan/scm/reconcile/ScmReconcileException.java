package io.adaptiq.titan.scm.reconcile;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Domain error raised by {@link ScmEventSource} when an external SCM API call fails (issue #1118).
 *
 * <p>Carries the failing input (provider + repo) + the cause so the scheduler can log a single
 * structured line and apply per-repo backoff. Per CONSTITUTION §"Errors": never catch + re-raise as
 * a bare {@code RuntimeException}.
 */
public class ScmReconcileException extends Exception {

  private static final long serialVersionUID = 1L;

  private final ScmProvider provider;
  private final String repoExternalId;

  public ScmReconcileException(
      @NonNull ScmProvider provider,
      @NonNull String repoExternalId,
      @NonNull String detail,
      @Nullable Throwable cause) {
    super(detail, cause);
    this.provider = provider;
    this.repoExternalId = repoExternalId;
  }

  @NonNull
  public ScmProvider provider() {
    return provider;
  }

  @NonNull
  public String repoExternalId() {
    return repoExternalId;
  }
}
