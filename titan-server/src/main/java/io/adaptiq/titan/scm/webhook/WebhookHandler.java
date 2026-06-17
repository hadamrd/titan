package io.adaptiq.titan.scm.webhook;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.store.rows.ScmWebhookEventRow;

/**
 * Single-method boundary the {@link WebhookRetryService} drives when re-running a previously failed
 * delivery (issue #1129).
 *
 * <p>Production wires this to the existing per-provider dispatch logic ({@code
 * GithubAppWebhookApi.receive}, {@code GitlabWebhookApi.receive}, {@code BitbucketWebhookApi}) so
 * the same code path runs on a fresh delivery and on a retried delivery — no parallel "retry"
 * pipeline that drifts. Tests use a deterministic in-memory Fake (Manifesto §"External I/O" — every
 * boundary has a Fake) that lets the test pre-program failure on attempt N.
 */
public interface WebhookHandler {

  /**
   * Re-dispatch the payload of {@code row} through the provider-specific handler.
   *
   * <p>The contract is plain: return {@link Outcome#SUCCESS} on success, {@link Outcome#RETRY} for
   * a transient failure the sweeper should retry (network blip, DB deadlock), {@link
   * Outcome#TERMINAL} for a permanent failure that should mark the row {@code FAILED} (malformed
   * payload, unknown installation, signature now invalid because the secret rotated). Never throw —
   * exceptions from the underlying handler MUST be caught and mapped to {@link Outcome#RETRY} with
   * the message preserved in {@link Result#error()}.
   */
  @NonNull
  Result handle(@NonNull ScmProvider provider, @NonNull ScmWebhookEventRow row);

  enum Outcome {
    SUCCESS,
    RETRY,
    TERMINAL
  }

  /**
   * Outcome + optional error message (non-null when {@link #outcome()} ≠ {@link Outcome#SUCCESS}).
   */
  record Result(@NonNull Outcome outcome, String error) {
    public static Result success() {
      return new Result(Outcome.SUCCESS, "");
    }

    public static Result retry(@NonNull String error) {
      return new Result(Outcome.RETRY, error);
    }

    public static Result terminal(@NonNull String error) {
      return new Result(Outcome.TERMINAL, error);
    }
  }
}
