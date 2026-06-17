package io.adaptiq.titan.scm.pulsar;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Wraps any failure (network, HTTP &gt;= 400, malformed JSON body) from a Pulsar node's {@code
 * /_pulsar/*} API (issue #1280, convergence axis 2 / scm-depth). Mirrors {@link
 * io.adaptiq.titan.scm.github.GithubApiException}.
 *
 * <p>Carries the HTTP status when known so the caller can distinguish a node 5xx (retry / backoff)
 * from a malformed-body parse failure ({@code status == -1}). Per Manifesto §"Errors": a non-2xx or
 * unparseable response surfaces as this typed boundary error, never a bare {@code RuntimeException}
 * and never a silently-empty result.
 */
public class PulsarApiException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** -1 when the failure was not an HTTP response (socket timeout, JSON parse failure). */
  private final int status;

  public PulsarApiException(@NonNull String message, int status) {
    super(message);
    this.status = status;
  }

  public PulsarApiException(@NonNull String message, int status, @Nullable Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  public int status() {
    return status;
  }
}
