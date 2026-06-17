package io.adaptiq.titan.scm.bitbucket;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Wraps a transport-level failure (socket error, interrupt, malformed body) talking to the
 * Bitbucket Cloud REST API (issue #1117). HTTP &gt;= 400 responses are NOT thrown — they are
 * returned as a {@link BitbucketClient.Response} so each reporter can decide its own graceful
 * degradation (401 → credential error, 404 → warn-and-continue, 429 → retry). This exception is
 * reserved for the cases where there is no HTTP status to inspect.
 *
 * <p>NEVER carries a token in its message — credentials are redacted at the {@link
 * BitbucketAuthProvider} boundary and never passed into this constructor.
 */
public class BitbucketApiException extends RuntimeException {

  /** -1 when there was no HTTP response (socket timeout, interrupt, parse failure). */
  private final int status;

  public BitbucketApiException(@NonNull String message, int status) {
    super(message);
    this.status = status;
  }

  public BitbucketApiException(@NonNull String message, int status, @NonNull Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
