package io.adaptiq.titan.scm.github;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Wraps any failure (network, HTTP &gt;= 400, malformed body) from the GitHub API. Carries the HTTP
 * status when known so the calling REST resource can translate to a sensible client error (e.g. 422
 * from {@code POST /app-manifests/{code}/conversions} ⇒ HTTP 400 to the Titan UI: the temp code was
 * invalid or already redeemed).
 */
public class GithubApiException extends RuntimeException {

  /** -1 when the failure was not an HTTP response (e.g. socket timeout, JSON parse failure). */
  private final int status;

  public GithubApiException(@NonNull String message, int status) {
    super(message);
    this.status = status;
  }

  public GithubApiException(@NonNull String message, int status, @NonNull Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
