package io.adaptiq.titan.scm.reconcile;

/**
 * Closed enum of SCM providers known to the reconcile subsystem (issue #1118).
 *
 * <p>Per CONSTITUTION §"No stringly-typed cross-module discriminators": the producer ({@link
 * io.adaptiq.titan.api.triggers.GithubWebhookApi} & friends) and the consumer ({@link
 * ReconcileScheduler}) MUST compare on this enum, never on a raw string. The persisted form is
 * {@link #name()} lowercased so dedupe is stable across the wire.
 */
public enum ScmProvider {
  GITHUB,
  GITLAB,
  BITBUCKET,
  PULSAR;

  /** Lowercase, kebab-free identifier persisted to {@code scm_event_seen.provider}. */
  public String wire() {
    return name().toLowerCase(java.util.Locale.ROOT);
  }
}
