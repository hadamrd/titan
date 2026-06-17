package io.adaptiq.titan.scm.pulsar;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Normalized discovery result for one open Pulsar change that needs a build (issue #1280). One of
 * these is emitted per open, not-yet-built change by {@link PulsarRepoScanner}. Mirrors the shape
 * the GitHub discovery path produces, but provider-neutral at the field level.
 *
 * @param repo the Pulsar repo the change lives on
 * @param changeId the change identifier (the {@code <id>} in {@code refs/pulsar/changes/<id>})
 * @param ref the fully-qualified change ref — always {@link PulsarClient#CHANGE_REF_PREFIX} +
 *     {@code changeId}
 * @param revision the tip oid the change ref currently points at
 */
public record PulsarChangeDiscovery(
    @NonNull String repo, @NonNull String changeId, @NonNull String ref, @NonNull String revision) {

  public PulsarChangeDiscovery {
    if (repo.isBlank()) {
      throw new IllegalArgumentException("repo must not be blank");
    }
    if (changeId.isBlank()) {
      throw new IllegalArgumentException("changeId must not be blank");
    }
    if (revision.isBlank()) {
      throw new IllegalArgumentException("revision must not be blank");
    }
  }

  /** Build a discovery item for {@code changeId} on {@code repo}, deriving the canonical ref. */
  @NonNull
  public static PulsarChangeDiscovery of(
      @NonNull String repo, @NonNull String changeId, @NonNull String revision) {
    return new PulsarChangeDiscovery(
        repo, changeId, PulsarClient.CHANGE_REF_PREFIX + changeId, revision);
  }

  /**
   * The canonical {@link io.adaptiq.titan.scm.reconcile.EventDedupeStore} dispatch key for a Pulsar
   * change: {@code <repo>:<changeId>:<revision>}. The revision is part of the key so a change
   * re-emits when its tip advances. This is the SINGLE source of the format so the scanner's {@code
   * markSeen} and the scheduler's {@code release} cannot drift apart.
   */
  @NonNull
  public static String dispatchEventId(
      @NonNull String repo, @NonNull String changeId, @NonNull String revision) {
    return repo + ":" + changeId + ":" + revision;
  }

  /** This discovery's dispatch key — see {@link #dispatchEventId(String, String, String)}. */
  @NonNull
  public String dispatchEventId() {
    return dispatchEventId(repo, changeId, revision);
  }
}
