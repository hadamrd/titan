package io.adaptiq.titan.build;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * CDI event fired by {@link BuildServiceImpl#update} on every persisted build-status transition.
 * Observers are best-effort and MUST NOT throw — {@code BuildServiceImpl} catches and logs anything
 * an observer fails with, matching the pattern already used for the {@code TitanMetrics} terminal
 * emission (issue #649).
 *
 * <p>The event carries the {@code id}, the {@code newStatus} after the write, and the {@code
 * triggerType} / {@code triggerMetaJson} the build was created with — enough context for an
 * observer to filter to its own provenance (e.g. {@link
 * io.adaptiq.titan.scm.github.GithubStatusReporter} filters on {@code triggerType == "github-app"},
 * closes #835) without an extra DB round-trip.
 *
 * <p><strong>Why a CDI event and not a direct call:</strong> the alternative is a hard reference
 * from {@code BuildServiceImpl} to {@code GithubStatusReporter}, which (a) couples the build
 * service to an SCM-specific concern, (b) makes adding the inevitable second observer (Slack,
 * GitLab, audit-log) a re-edit of {@code BuildServiceImpl}. CDI events are the Quarkus-idiomatic
 * fan-out — same {@code @Observes} pattern as {@code TitanMetrics.onStart(@Observes StartupEvent)}
 * already in the tree. This is not a "new event bus" — it's one event using the framework's
 * built-in dispatcher.
 */
public record BuildStateChangedEvent(
    long buildId,
    @NonNull String newStatus,
    @Nullable String triggerType,
    @Nullable String triggerMetaJson,
    long jobId,
    int buildNumber) {}
