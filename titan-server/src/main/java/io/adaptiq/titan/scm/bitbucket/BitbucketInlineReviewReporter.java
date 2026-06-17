package io.adaptiq.titan.scm.bitbucket;

import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.scm.Finding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Posts {@link Finding}s emitted by an analysis step (sast, lint, …) back to a Bitbucket Cloud pull
 * request as inline review comments (issue #1117). The {@link Finding} DTO is the SAME shared type
 * the GitHub adapter would consume — no Bitbucket-specific finding type leaks past this boundary.
 *
 * <h2>Experimental — flag-gated, no engine producer wired yet</h2>
 *
 * <strong>This capability is OFF by default</strong> ({@code
 * titan.bitbucket.inline-review.enabled=false}). Unlike {@link BitbucketPrCommentReporter} (which
 * is fully event-driven via {@code @Observes BuildStateChangedEvent}), there is no terminal CDI
 * event in the engine today that carries structured {@link Finding}s — a step-runner "findings
 * emitted" event is a separate, out-of-scope ticket (the GitHub adapter likewise has no inline
 * reporter yet; see {@code docs/scm-compat.md}). Until that producer exists, {@link #postFindings}
 * has no production caller, so it is gated behind the experimental flag to make that state explicit
 * rather than shipping silently-dead code. Flip the flag (and wire the producer) to activate it;
 * the payload/anchoring/fallback logic below is fully implemented and unit-tested so it is a
 * drop-in once the event lands.
 *
 * <h2>Inline anchoring + the renamed-file sad path</h2>
 *
 * Each line-anchored finding becomes {@code POST .../pullrequests/{id}/comments} with {@code
 * inline.path} + {@code inline.to}. Bitbucket rejects an inline comment whose path/line is not part
 * of the PR diff with {@code 400}. Rather than crash (or lose the finding), the reporter collects
 * every finding that could not be anchored — file renamed, file not in diff, or no line anchor —
 * and posts them in ONE fallback summary comment so the reviewer still sees them.
 *
 * <h2>Error contract</h2>
 *
 * Best-effort: failures are logged and swallowed, never thrown. Tokens never appear in logs.
 */
@ApplicationScoped
public class BitbucketInlineReviewReporter {

  private static final Logger LOGGER =
      Logger.getLogger(BitbucketInlineReviewReporter.class.getName());

  static final String CREDENTIALS_SCOPE = "bitbucket-webhook";

  private final CredentialsService credentials;
  private final BitbucketClientFactory clientFactory;

  /**
   * Experimental kill-switch. {@code false} by default until a findings-emitting engine event wires
   * a real production caller (see class javadoc). When disabled, {@link #postFindings} is a no-op.
   */
  private final boolean enabled;

  @Inject
  public BitbucketInlineReviewReporter(
      CredentialsService credentials,
      BitbucketClientFactory clientFactory,
      @ConfigProperty(name = "titan.bitbucket.inline-review.enabled", defaultValue = "false")
          boolean enabled) {
    this.credentials = credentials;
    this.clientFactory = clientFactory;
    this.enabled = enabled;
  }

  /** Outcome of a review post — for test assertions and structured logging. */
  public record Result(int inlinePosted, int fellBackToSummary, boolean summaryPosted) {
    public static final Result NOOP = new Result(0, 0, false);
  }

  /**
   * Resolve credentials for {@code ctx} and post {@code findings}. Returns {@link Result#NOOP} when
   * there are no findings or the credential cannot be resolved (logged).
   */
  @NonNull
  public Result postFindings(@NonNull BitbucketPrContext ctx, @NonNull List<Finding> findings) {
    if (!enabled) {
      LOGGER.log(
          Level.FINE,
          "[bitbucket-inline] disabled (titan.bitbucket.inline-review.enabled=false) —"
              + " skipping {0} finding(s) for pr #{1}",
          new Object[] {findings.size(), ctx.prId()});
      return Result.NOOP;
    }
    if (findings.isEmpty()) {
      return Result.NOOP;
    }
    Optional<String> tokenOpt =
        credentials.resolvePlaintext(CREDENTIALS_SCOPE, ctx.credentialsId());
    if (tokenOpt.isEmpty() || tokenOpt.get().isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-inline] credentialsId ''{0}'' did not resolve — skipping {1} finding(s)",
          new Object[] {ctx.credentialsId(), findings.size()});
      return Result.NOOP;
    }
    BitbucketAuthProvider auth;
    try {
      auth = BitbucketAuthProvider.fromColonPair(tokenOpt.get());
    } catch (IllegalArgumentException badCred) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-inline] credential ''{0}'' malformed (expected username:app_password)"
              + " — skipping",
          ctx.credentialsId());
      return Result.NOOP;
    }
    return postFindings(clientFactory.create(), auth, ctx, findings);
  }

  /** Visible-for-testing — post with an explicit client + auth. */
  @NonNull
  Result postFindings(
      @NonNull BitbucketClient client,
      @NonNull BitbucketAuthProvider auth,
      @NonNull BitbucketPrContext ctx,
      @NonNull List<Finding> findings) {
    int inlinePosted = 0;
    List<Finding> fallback = new ArrayList<>();

    for (Finding f : findings) {
      if (!f.hasLineAnchor()) {
        fallback.add(f);
        continue;
      }
      BitbucketClient.Response resp =
          client.send("POST", ctx.commentsPath(), auth, inlinePayload(f));
      if (resp.isSuccess()) {
        inlinePosted++;
      } else if (resp.status() == 400) {
        // Path/line not in the PR diff (renamed file, file outside the diff) → fall back.
        LOGGER.log(
            Level.FINE,
            "[bitbucket-inline] pr #{0}: finding on {1}:{2} not in diff (HTTP 400)"
                + " — deferring to summary",
            new Object[] {ctx.prId(), f.path(), f.line()});
        fallback.add(f);
      } else {
        logHttpFailure(resp.status(), ctx);
        // Non-400 errors (401/404/5xx) are not recoverable via the summary path → drop, logged.
      }
    }

    boolean summaryPosted = false;
    if (!fallback.isEmpty()) {
      BitbucketClient.Response resp =
          client.send("POST", ctx.commentsPath(), auth, summaryPayload(fallback));
      if (resp.isSuccess()) {
        summaryPosted = true;
      } else {
        logHttpFailure(resp.status(), ctx);
      }
    }
    return new Result(inlinePosted, fallback.size(), summaryPosted);
  }

  private void logHttpFailure(int status, @NonNull BitbucketPrContext ctx) {
    if (status == 401 || status == 403) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-inline] pr #{0}: Bitbucket returned HTTP {1} — credential rejected"
              + " (scm post failed)",
          new Object[] {ctx.prId(), status});
    } else if (status == 404) {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-inline] pr #{0}: PR not found (HTTP 404, likely closed) — continuing",
          ctx.prId());
    } else {
      LOGGER.log(
          Level.WARNING,
          "[bitbucket-inline] pr #{0}: Bitbucket returned HTTP {1} — skipping",
          new Object[] {ctx.prId(), status});
    }
  }

  // ── payload rendering ───────────────────────────────────────────────────────

  /** {@code {"content":{"raw":...},"inline":{"path":...,"to":...}}}. */
  @NonNull
  static String inlinePayload(@NonNull Finding f) {
    ObjectNode root = BitbucketJson.MAPPER.createObjectNode();
    root.putObject("content").put("raw", renderFinding(f));
    ObjectNode inline = root.putObject("inline");
    inline.put("path", f.path());
    inline.put("to", f.line());
    return write(root);
  }

  /** One summary comment listing every finding that could not be inlined. */
  @NonNull
  static String summaryPayload(@NonNull List<Finding> findings) {
    StringBuilder sb = new StringBuilder(256);
    sb.append("### Titan findings (not anchored to the PR diff)\n\n");
    for (Finding f : findings) {
      sb.append("- ").append(severityLabel(f.severity())).append(" `").append(f.path());
      if (f.hasLineAnchor()) {
        sb.append(':').append(f.line());
      }
      sb.append("` — ").append(f.message()).append('\n');
    }
    ObjectNode root = BitbucketJson.MAPPER.createObjectNode();
    root.putObject("content").put("raw", sb.toString());
    return write(root);
  }

  @NonNull
  static String renderFinding(@NonNull Finding f) {
    return severityLabel(f.severity()) + " " + f.message();
  }

  @NonNull
  static String severityLabel(@NonNull Finding.Severity severity) {
    return switch (severity) {
      case CRITICAL -> "🟥 CRITICAL";
      case MAJOR -> "🟧 MAJOR";
      case MINOR -> "🟨 MINOR";
      case INFO -> "🟦 INFO";
    };
  }

  @NonNull
  private static String write(@NonNull ObjectNode node) {
    try {
      return BitbucketJson.MAPPER.writeValueAsString(node);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("bitbucket inline payload serialisation failed", e);
    }
  }
}
