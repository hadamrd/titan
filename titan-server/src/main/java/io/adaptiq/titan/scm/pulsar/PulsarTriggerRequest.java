package io.adaptiq.titan.scm.pulsar;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import java.util.Objects;

/**
 * A normalized Titan build-trigger request derived from one discovered Pulsar change (issue #1281,
 * convergence axis 2 / scm-depth). Mirrors the GitHub trigger contract — {@code (provider, repo,
 * ref, revision)} plus a stable event identity — so a Pulsar change flows through the SAME
 * trigger/dispatch path GitHub uses, with no Pulsar specifics leaking into the planner / build
 * core.
 *
 * <p>The {@link #eventId()} is deterministic — {@code <repo>:<changeId>:<revision>} — and matches
 * the dispatch key {@link PulsarRepoScanner} dedupes on, so a change re-emits a trigger only when
 * its tip advances. {@link #revision()} is exactly the change-ref oid resolved from the node's
 * {@code /refs} map (acceptance: the trigger's revision == the change-ref oid).
 *
 * @param provider always {@link ScmProvider#PULSAR}
 * @param repo the Pulsar repo the change lives on
 * @param changeId the change identifier (the {@code <id>} in {@code refs/pulsar/changes/<id>})
 * @param ref the fully-qualified change ref — {@link PulsarClient#CHANGE_REF_PREFIX} + changeId
 * @param revision the tip oid the change ref points at
 * @param eventId the deterministic, stable identity for this (repo, change, revision)
 */
public record PulsarTriggerRequest(
    @NonNull ScmProvider provider,
    @NonNull String repo,
    @NonNull String changeId,
    @NonNull String ref,
    @NonNull String revision,
    @NonNull String eventId) {

  public PulsarTriggerRequest {
    Objects.requireNonNull(provider, "provider");
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(changeId, "changeId");
    Objects.requireNonNull(ref, "ref");
    Objects.requireNonNull(revision, "revision");
    Objects.requireNonNull(eventId, "eventId");
    if (provider != ScmProvider.PULSAR) {
      throw new IllegalArgumentException("provider must be PULSAR, was " + provider);
    }
  }

  /**
   * Map a scanned change to its trigger request, deriving the deterministic event identity. The
   * revision is carried through verbatim from the change-ref oid the scanner resolved.
   */
  @NonNull
  public static PulsarTriggerRequest fromChange(@NonNull PulsarChangeDiscovery change) {
    Objects.requireNonNull(change, "change");
    String eventId = change.repo() + ":" + change.changeId() + ":" + change.revision();
    return new PulsarTriggerRequest(
        ScmProvider.PULSAR,
        change.repo(),
        change.changeId(),
        change.ref(),
        change.revision(),
        eventId);
  }
}
