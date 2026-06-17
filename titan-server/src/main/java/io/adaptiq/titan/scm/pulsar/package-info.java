/**
 * Native Pulsar SCM adapter for Titan — convergence axis 2 / scm-depth (issue #1280).
 *
 * <p>Makes Pulsar a first-class SCM alongside GitHub/GitLab/Bitbucket: Titan polls a Pulsar node's
 * {@code /_pulsar/*} ledger API, discovers open changes that need a build, and (in later slices)
 * builds them + reports the verdict back as the merge-gating {@code build} check. This package
 * mirrors {@code io.adaptiq.titan.scm.github} structurally; the core stays provider-neutral — the
 * provider discriminator is {@link io.adaptiq.titan.scm.reconcile.ScmProvider#PULSAR}, never a raw
 * string (CONSTITUTION §"No stringly-typed cross-module discriminators").
 *
 * <p>This slice (client + scan/poll discovery):
 *
 * <ul>
 *   <li>{@link io.adaptiq.titan.scm.pulsar.PulsarClient} / {@link
 *       io.adaptiq.titan.scm.pulsar.PulsarClientFactory} — typed, bounded-timeout HTTP to a node:
 *       list repos, list open changes, resolve a change tip via {@code refs/pulsar/changes/<id>}.
 *   <li>{@link io.adaptiq.titan.scm.pulsar.PulsarRepoScanner} — polls for open changes that need a
 *       build, dedupes against the shared {@link io.adaptiq.titan.scm.reconcile.EventDedupeStore},
 *       and emits a normalized {@link io.adaptiq.titan.scm.pulsar.PulsarChangeDiscovery} per fresh
 *       change.
 *   <li>{@link io.adaptiq.titan.scm.pulsar.PulsarScannerScheduler} — the non-reentrant poll driver.
 * </ul>
 *
 * <p>Out of scope here (follow-ups): build trigger + clone (#T2), verdict → check reporting (#T3),
 * UI (#T4).
 */
package io.adaptiq.titan.scm.pulsar;
