package io.adaptiq.titan.trigger;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * SPI for posting a build's current state back to the SCM that originally triggered it (issue
 * #1080).
 *
 * <p>The engine fires a state-change signal on every persisted build transition (see {@code
 * io.adaptiq.titan.build.BuildStateChangedEvent}); each implementation filters on {@link
 * #triggerTypePrefix()} so a build only round-trips to ONE SCM, never broadcasts. Implementations
 * MUST be best-effort: a 4xx/5xx from the upstream SCM is logged and swallowed — never thrown —
 * because the build's status has already been committed to the database before any reporter
 * observes it, and losing a cosmetic commit-status badge is strictly better than rolling back a
 * real engine write.
 *
 * <p><strong>Why a small DTO in this module, not the CDI event directly.</strong> {@code
 * BuildStateChangedEvent} lives in {@code titan-server} and depends on Jakarta CDI. The trigger SPI
 * module is intentionally pure-Java + Jackson + ANTLR (see the module's {@code build.gradle.kts}
 * preamble); pulling in CDI here would invert the layering. The five scalar fields below are the
 * exact projection the reporters need — adding fields later is a non-breaking append.
 *
 * <p>Reporters are wired as {@code @ApplicationScoped} CDI beans on the server side; their CDI
 * {@code @Observes BuildStateChangedEvent} method translates to a {@link BuildStatusEvent} and
 * dispatches to {@link #report(BuildStatusEvent)}.
 */
public interface StatusReporter {

  /**
   * The {@code triggerType} prefix this reporter handles, matched as a prefix because trigger types
   * are discriminated strings (e.g. {@code "github-app:push"}, {@code "github-app:pull_request"}).
   * Implementations short-circuit on a non-matching prefix BEFORE any I/O.
   */
  @NonNull
  String triggerTypePrefix();

  /**
   * Report the new state for one build. Implementations MUST swallow every exception — the engine
   * dispatcher catches {@link RuntimeException} as a last line of defence, but a well-behaved
   * reporter never escapes one. Tokens MUST NEVER appear in log output, not even on authentication
   * failure.
   */
  void report(@NonNull BuildStatusEvent event);

  /**
   * The minimal projection of a build state-change the SCM reporters consume. Mirrors the scalar
   * fields of {@code BuildStateChangedEvent} without dragging the CDI dependency into this module.
   *
   * @param buildId database id of the build
   * @param newStatus canonical status string (e.g. {@code QUEUED}, {@code RUNNING}, {@code
   *     SUCCESS}, {@code FAILED}, {@code ABORTED}, {@code CANCELLED}, {@code UNSTABLE})
   * @param triggerType the discriminated trigger string the build was created with — reporters
   *     match against {@link #triggerTypePrefix()} on this
   * @param triggerMetaJson the structured-facts JSON the inbound webhook serialised when it
   *     enqueued the build (typically {@code branch}, {@code commitSha}, {@code actor}, plus SCM-
   *     specific projectId / workspace / repoSlug / credentialsId fields)
   * @param jobId database id of the parent job (for SCM-link table lookups)
   * @param buildNumber per-job monotonic build number (for display only)
   */
  record BuildStatusEvent(
      long buildId,
      @NonNull String newStatus,
      @Nullable String triggerType,
      @Nullable String triggerMetaJson,
      long jobId,
      int buildNumber) {}
}
