package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * CDI event fired the instant a build is <em>enqueued</em> — i.e. right after {@link
 * BuildEnqueuer#enqueue} inserts the {@code QUEUED} row, BEFORE any worker picks it up and BEFORE
 * the first {@link BuildStateChangedEvent} (which only fires on a persisted {@code BuildServiceImpl}
 * transition, i.e. {@code QUEUED → RUNNING} at worker pickup).
 *
 * <p><strong>Why this event exists (issue #1, GitHub-Actions parity).</strong> GitHub shows a check
 * the instant you push. Before this event the first signal the Pulsar reporter ever saw was the
 * {@code QUEUED → RUNNING} transition at worker pickup, so a change whose build sat in a full queue
 * showed <em>no</em> {@code build} check at all on the node for seconds-to-minutes — the merge gate
 * looked empty rather than "pending". Firing this event at enqueue lets {@link
 * io.adaptiq.titan.scm.pulsar.PulsarCheckReporter} post {@code conclusion:pending} immediately.
 *
 * <p><strong>Narrow provenance — fired ONLY at the two Pulsar enqueue call sites</strong> ({@code
 * PulsarWebhookApi.receive} and {@code PulsarScanScheduler.dispatchDiscovery}). The other {@link
 * BuildEnqueuer#enqueue} callers ({@code DiscoveryServiceImpl}, {@code GithubAppWebhookApi}) MUST
 * NOT fire it — their trigger types are {@code discovery}/{@code github-app} and have no enqueue-time
 * check contract. The lone observer ({@code PulsarCheckReporter}) additionally filters on a {@code
 * pulsar*} {@code triggerType}, so even a stray fire from another provenance is a guaranteed no-op.
 *
 * <p>Mirrors {@link BuildStateChangedEvent}: a plain record carrying just enough context (build +
 * job identity, trigger provenance, the trigger meta JSON) for the observer to resolve the change
 * and repo without an extra DB round-trip. Observers are best-effort and MUST NOT throw into the
 * enqueue path — the reporter catches everything at its observer boundary, and the two fire sites
 * additionally guard the {@code fire()} call so a CDI dispatch failure never fails the build insert
 * nor changes the webhook's {@code 202} / the scanner's per-source isolation.
 */
public record BuildEnqueuedEvent(
    long buildId,
    long jobId,
    @Nullable String triggerType,
    @Nullable String triggerMetaJson,
    int buildNumber) {}
