package io.adaptiq.titan.discovery;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Seam for resolving the current commit SHA at a remote git ref — the one moving part of the {@link
 * DiscoveryService} polling loop.
 *
 * <p>The default {@link ProcessGitHeadResolver} shells out to {@code git ls-remote}, the same
 * primitive the pipeline-model {@code LibraryFetcher} uses (see design/47 §6). Tests inject a stub
 * so the polling loop can be exercised against H2 without touching the network.
 */
@FunctionalInterface
public interface GitHeadResolver {

  /**
   * Resolve {@code branch} on {@code url} to a 40-hex commit SHA. Implementations must return a
   * non-null SHA on success; failures throw {@link GitHeadException}.
   */
  @NonNull
  String resolve(@NonNull String url, @NonNull String branch) throws GitHeadException;

  /** Thrown when the remote HEAD cannot be resolved (network error, bad ref, auth, …). */
  class GitHeadException extends Exception {
    public GitHeadException(@NonNull String message) {
      super(message);
    }

    public GitHeadException(@NonNull String message, @NonNull Throwable cause) {
      super(message, cause);
    }
  }
}
