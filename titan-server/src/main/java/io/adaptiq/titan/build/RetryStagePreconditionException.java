package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Thrown by {@link BuildService#retryStage} when the named stage exists but is not in the {@code
 * FAILED} state — the REST layer maps this to HTTP 409 (#744). Distinct from {@link
 * io.adaptiq.titan.api.ApiNotFoundException} (404 — build or node missing) so the API can choose
 * the right status code without inspecting the message.
 */
public class RetryStagePreconditionException extends RuntimeException {

  public RetryStagePreconditionException(@NonNull String message) {
    super(message);
  }
}
