package io.adaptiq.titan.scm.gitlab;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Typed boundary error from the GitLab REST API client (issue #1134). Carries the HTTP status so
 * callers can branch on {@code 401} (auth — do not retry blindly) vs. transient {@code 5xx} /
 * timeouts (retry on the next reconcile tick).
 */
public class GitlabApiException extends Exception {

  private static final long serialVersionUID = 1L;

  private final int status;

  public GitlabApiException(@NonNull String message, int status, @Nullable Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  /** HTTP status code; {@code -1} when the failure is a transport-level I/O error. */
  public int status() {
    return status;
  }
}
