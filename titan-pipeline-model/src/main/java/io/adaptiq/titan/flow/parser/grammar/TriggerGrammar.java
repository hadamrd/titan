package io.adaptiq.titan.flow.parser.grammar;

import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.optional;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.ref;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.required;
import static io.adaptiq.titan.flow.parser.grammar.GrammarKey.string;

import java.util.List;

/**
 * The {@code triggers[]} grammar contexts — extracted from {@link TitanGrammar} (design/59) so that
 * monolith stays under the 1000-line module cap.
 *
 * <p>A trigger entry is a discriminated union: exactly one of {@code cron:}, {@code github:},
 * {@code gitlab:} or {@code bitbucket:}. The three webhook discriminators ({@link #GITHUB_TRIGGER},
 * {@link #GITLAB_TRIGGER}, {@link #BITBUCKET_TRIGGER}) share the same inner shape — optional {@code
 * branches} / {@code events} string-or-list and a required {@code credentialsId}. Both the parser
 * and the schema generator project from these same declarations.
 */
public final class TriggerGrammar {

  private TriggerGrammar() {}

  /**
   * The build-trigger grammar context (design/50 D7, issues #397, #1078, #1079).
   *
   * <p>A trigger entry carries exactly one discriminator key: {@code cron:} (a standard cron
   * schedule), {@code github:}, {@code gitlab:} or {@code bitbucket:} (a webhook trigger). All keys
   * are declared {@code optional} here; the parser enforces "exactly one" at parse time, and the
   * schema generator emits a {@code oneOf} constraint that pins the same rule for editor tooling.
   */
  public static final List<GrammarKey> TRIGGER =
      List.of(
          optional(
              "cron",
              "A standard 5-field cron schedule (with 'H' for a hashed/jittered value), "
                  + "with the @daily/@hourly aliases and a 'TZ=' prefix all supported.",
              string()),
          optional(
              "github",
              "A GitHub webhook trigger (issue #397). Fires when a verified delivery "
                  + "from github.com arrives at /api/v1/triggers/github whose repository "
                  + "matches this job and whose branch matches one of the configured globs.",
              ref("githubTrigger")),
          optional(
              "gitlab",
              "A GitLab webhook trigger (issue #1078). Fires when a verified delivery "
                  + "from gitlab.com (or a self-hosted instance) arrives at "
                  + "/api/v1/triggers/gitlab whose X-Gitlab-Token matches the configured "
                  + "shared-secret credential and whose event kind / ref matches the "
                  + "configured filters.",
              ref("gitlabTrigger")),
          optional(
              "bitbucket",
              "A Bitbucket Cloud webhook trigger (issue #1079). Fires when a verified "
                  + "delivery from bitbucket.org arrives at /api/v1/triggers/bitbucket whose "
                  + "X-Hub-Signature HMAC matches the configured secret credential and whose "
                  + "event kind / ref matches the configured filters.",
              ref("bitbucketTrigger")));

  /**
   * The {@code github:} value grammar context (issue #397) — keys of the inner object under the
   * {@code github:} discriminator on a trigger entry.
   */
  public static final List<GrammarKey> GITHUB_TRIGGER =
      List.of(
          optional(
              "branches",
              "Glob patterns the delivery's branch must match. Empty / omitted means any "
                  + "branch. Patterns follow Titan's glob convention — e.g. 'trunk', "
                  + "'feat/**', 'release-*'.",
              ref("stringOrList")),
          optional(
              "events",
              "GitHub event types this trigger accepts — 'push' and / or 'pull_request'. "
                  + "Defaults to ['push'].",
              ref("stringOrList")),
          required(
              "credentialsId",
              "The credentials-store id of the HMAC secret GitHub signs deliveries with. "
                  + "Resolved at receive time via CredentialsService — the plaintext "
                  + "secret is NEVER stored in the pipeline config.",
              string()));

  /**
   * The {@code gitlab:} value grammar context (issue #1078) — keys of the inner object under the
   * {@code gitlab:} discriminator on a trigger entry.
   */
  public static final List<GrammarKey> GITLAB_TRIGGER =
      List.of(
          optional(
              "branches",
              "Glob patterns the delivery's ref must match. Empty / omitted means any ref. "
                  + "Applies to the branch name for push, the tag name for tag_push, and "
                  + "the source-branch name for merge_request.",
              ref("stringOrList")),
          optional(
              "events",
              "GitLab event kinds this trigger accepts — any of 'push', 'merge_request', "
                  + "'tag_push'. Defaults to ['push'].",
              ref("stringOrList")),
          required(
              "credentialsId",
              "The credentials-store id of the shared-secret token GitLab sends in "
                  + "X-Gitlab-Token. Resolved at receive time via CredentialsService — the "
                  + "plaintext token is NEVER stored in the pipeline config and NEVER logged.",
              string()));

  /**
   * The {@code bitbucket:} value grammar context (issue #1079) — keys of the inner object under the
   * {@code bitbucket:} discriminator on a trigger entry.
   */
  public static final List<GrammarKey> BITBUCKET_TRIGGER =
      List.of(
          optional(
              "branches",
              "Glob patterns the delivery's ref must match. Empty / omitted means any ref. "
                  + "Applies to the pushed branch name for push and the source (head) branch "
                  + "for pull_request.",
              ref("stringOrList")),
          optional(
              "events",
              "Bitbucket event kinds this trigger accepts — any of 'push', 'pull_request'. "
                  + "Defaults to ['push'].",
              ref("stringOrList")),
          required(
              "credentialsId",
              "The credentials-store id of the HMAC secret Bitbucket signs deliveries with "
                  + "(X-Hub-Signature). Resolved at receive time via CredentialsService — the "
                  + "plaintext secret is NEVER stored in the pipeline config and NEVER logged.",
              string()));
}
