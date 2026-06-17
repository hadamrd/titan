package io.adaptiq.titan.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.flow.model.NotifyHook;
import io.adaptiq.titan.flow.model.PipelineModel;
import jakarta.enterprise.inject.spi.CDI;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fires declarative {@code notify:} lifecycle hooks (#245) at build / stage terminal state. Pure
 * best-effort: a hook is fire-and-forget — a failure to deliver one (HTTP error, DNS hiccup, slow
 * sink) MUST NOT abort the orchestrator's terminal-write path. The whole call is wrapped in {@code
 * try/catch} and only logs warnings on failure.
 *
 * <p>Two hook types ship:
 *
 * <ul>
 *   <li>{@code type: webhook} — plain HTTP {@code POST} of a small JSON envelope ({@code buildId},
 *       {@code status}, {@code pipeline}, optional {@code stage}). The {@code url} is inline.
 *   <li>{@code type: slack} (#358) — Slack inbound-webhook block-kit payload. The webhook URL is a
 *       workspace-token-bearing secret and MUST NOT live in {@code config_json}; instead the YAML
 *       carries {@code credentialsId} and {@link CredentialsService} resolves the URL at dispatch
 *       time. The resolved URL is used immediately, never logged, and not retained beyond this
 *       dispatch (CONSTITUTION §6).
 * </ul>
 */
public class NotificationDispatcher {

  private static final Logger LOGGER = Logger.getLogger(NotificationDispatcher.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Tight per-hook timeout. We do not want a slow sink to hold up the orchestrator's terminal path;
   * the fire-and-forget contract means losing the occasional notification is acceptable.
   */
  private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient http;

  /**
   * The credentials service used to resolve Slack webhook URLs at dispatch time (#358). {@code
   * null} means slack hooks will be logged + skipped rather than dispatched — the dispatcher does
   * NOT fail closed because notify is contractually best-effort.
   */
  @Nullable private final CredentialsService credentials;

  public NotificationDispatcher() {
    this(
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
        lookupCredentialsService());
  }

  /**
   * Best-effort CDI lookup of {@link CredentialsService} for the default-constructor path
   * (production wiring: {@code QueueProcessor} → {@code new TitanOrchestrator(daos, buildId)} →
   * {@code new NotificationDispatcher()}). Returns {@code null} when no CDI container is running
   * (raw JUnit) or the bean is not resolvable — slack hooks will then be logged + skipped, never
   * dispatched (#358 fail-safe).
   */
  @Nullable
  private static CredentialsService lookupCredentialsService() {
    try {
      return CDI.current().select(CredentialsService.class).get();
    } catch (RuntimeException e) {
      // CDI not running / bean not resolvable — fine, slack hooks just won't fire.
      return null;
    }
  }

  /** Test seam — inject a custom HttpClient (e.g. one bound to a local HttpServer). */
  public NotificationDispatcher(@NonNull HttpClient http) {
    this(http, null);
  }

  /**
   * Production seam — inject the HTTP client and the {@link CredentialsService} used to resolve
   * Slack webhook URLs at dispatch time (#358).
   */
  public NotificationDispatcher(
      @NonNull HttpClient http, @Nullable CredentialsService credentials) {
    this.http = http;
    this.credentials = credentials;
  }

  /** Maximum length of the {@code failureReason} field on a {@code BAKE_FAILURE} envelope. */
  private static final int BAKE_FAILURE_REASON_MAX = 1024;

  /**
   * Fire every pipeline-level hook that matches the build's terminal status. {@code result} is
   * either {@code "SUCCESS"} or {@code "FAILED"} (the orchestrator's two terminal labels). All
   * delivery is best-effort — the method never throws.
   */
  public void fireBuildHooks(long buildId, @NonNull String result, @NonNull PipelineModel model) {
    fireMany(model.getNotify(), result, buildId, /* jobName */ null, /* stageName */ null);
  }

  /**
   * Rich variant of {@link #fireBuildHooks(long, String, PipelineModel)} — accepts the pre-computed
   * {@link NotificationContext} so the dispatched payload can carry job name, build duration, the
   * failed-stage name, and a deep-link to the build-detail page (#1102 SRE-actionable Slack-on-fail
   * surface). Also honors the {@code recovery} predicate: a hook declared {@code on: [recovery]}
   * fires only on a fail→success transition (i.e. {@link NotificationContext#recovery()}), never on
   * every green build.
   *
   * <p>The {@code ctx.previousResult} (the previous finished build's status, optional) is the only
   * piece of state that requires a DAO lookup — the caller does it once at terminal-write time and
   * hands the resolved context here. The dispatcher never calls back into the DB.
   *
   * <p>Best-effort: never throws. Delivery failures (404 / 500 / timeout / DNS) are logged at
   * WARNING and swallowed — the orchestrator's terminal write MUST NOT be coupled to Slack uptime.
   */
  public void fireBuildHooks(@NonNull NotificationContext ctx, @NonNull PipelineModel model) {
    fireManyRich(model.getNotify(), ctx);
  }

  /**
   * Fire every hook that matches a {@code FAILED} predicate with a synthetic {@code BAKE_FAILURE}
   * event (#360). Called from the BAKE / synthesis crash path in {@code QueueProcessor}, where the
   * full {@link PipelineModel} is never built (and {@link #fireBuildHooks} cannot run). The caller
   * is responsible for tolerantly extracting {@code hooks} from the pipeline script via {@code
   * TitanYamlParser.parseNotifyHooksTolerant} — an empty list is a no-op.
   *
   * <p>The webhook envelope carries {@code kind=BAKE_FAILURE} plus the truncated parser error in
   * {@code failureReason}. Slack hooks fall back to a block-kit payload mentioning the failure.
   * Hooks declared {@code on: [success]} do NOT fire — a bake failure is a FAILED terminal state.
   *
   * <p>Best-effort: a delivery error never throws — same fire-and-forget contract as the regular
   * dispatch path.
   *
   * @param failureReason the parser exception message (truncated to 1 KB for safety — stack-frames
   *     don't belong in a Slack message).
   */
  public void fireBakeFailureHooks(
      long buildId,
      @Nullable String jobName,
      @NonNull String failureReason,
      @NonNull List<NotifyHook> hooks) {
    if (hooks.isEmpty()) {
      return;
    }
    String trimmed = truncateReason(failureReason);
    for (NotifyHook hook : hooks) {
      try {
        if (!hook.firesOnFailure()) {
          continue;
        }
        fireBakeFailureOne(hook, buildId, jobName, trimmed);
      } catch (RuntimeException e) {
        // Defensive: notify is best-effort even on the bake-failure path.
        LOGGER.log(
            Level.WARNING,
            "[titan] notify: BAKE_FAILURE hook fire failed (build "
                + buildId
                + ", type="
                + hook.getType()
                + ")",
            e);
      }
    }
  }

  @NonNull
  private static String truncateReason(@NonNull String reason) {
    if (reason.length() <= BAKE_FAILURE_REASON_MAX) {
      return reason;
    }
    return reason.substring(0, BAKE_FAILURE_REASON_MAX) + "…";
  }

  private void fireBakeFailureOne(
      @NonNull NotifyHook hook,
      long buildId,
      @Nullable String jobName,
      @NonNull String failureReason) {
    switch (hook.getType()) {
      case "webhook" -> fireBakeFailureWebhook(hook, buildId, jobName, failureReason);
      case "slack" -> fireBakeFailureSlack(hook, buildId, jobName, failureReason);
      default ->
          LOGGER.log(
              Level.WARNING,
              "[titan] notify: BAKE_FAILURE skipping unsupported hook type ''{0}''",
              new Object[] {hook.getType()});
    }
  }

  private void fireBakeFailureWebhook(
      @NonNull NotifyHook hook,
      long buildId,
      @Nullable String jobName,
      @NonNull String failureReason) {
    String url = hook.getUrl();
    if (url == null || url.isBlank()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: BAKE_FAILURE webhook hook with no url — skipping (build {0})",
          buildId);
      return;
    }
    ObjectNode body = JSON.createObjectNode();
    body.put("buildId", buildId);
    body.put("status", "FAILED");
    body.put("kind", "BAKE_FAILURE");
    body.put("failureReason", failureReason);
    if (jobName != null) {
      body.put("jobName", jobName);
    }
    postJson(buildId, url, body, "webhook");
  }

  private void fireBakeFailureSlack(
      @NonNull NotifyHook hook,
      long buildId,
      @Nullable String jobName,
      @NonNull String failureReason) {
    String credentialsId = hook.getCredentialsId();
    if (credentialsId == null || credentialsId.isBlank()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: BAKE_FAILURE slack hook with no credentialsId — skipping (build {0})",
          buildId);
      return;
    }
    if (credentials == null) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: BAKE_FAILURE slack hook on build {0} not dispatched — no "
              + "CredentialsService wired (credentialsId=''{1}''); skipping",
          new Object[] {buildId, credentialsId});
      return;
    }

    ScopedKey ref = parseCredentialsId(credentialsId);
    Optional<String> resolved;
    try {
      resolved = credentials.resolvePlaintext(ref.scope, ref.key);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: BAKE_FAILURE slack credential resolution failed (build "
              + buildId
              + ", credentialsId="
              + credentialsId
              + ")",
          e);
      return;
    }
    if (resolved.isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: BAKE_FAILURE slack credential ''{0}'' not found — skipping (build {1})",
          new Object[] {credentialsId, buildId});
      return;
    }

    String label = jobName != null ? jobName : ("build " + buildId);
    ObjectNode body =
        buildBakeFailureSlackPayload(label, buildId, failureReason, hook.getChannel());
    postJson(buildId, resolved.get(), body, "slack");
  }

  @NonNull
  private static ObjectNode buildBakeFailureSlackPayload(
      @NonNull String label,
      long buildId,
      @NonNull String failureReason,
      @Nullable String channel) {
    ObjectNode body = JSON.createObjectNode();
    body.put("text", label + " — BAKE FAILURE");
    if (channel != null && !channel.isBlank()) {
      body.put("channel", channel);
    }
    ArrayNode blocks = body.putArray("blocks");

    ObjectNode section = blocks.addObject();
    section.put("type", "section");
    ObjectNode sectionText = section.putObject("text");
    sectionText.put("type", "mrkdwn");
    sectionText.put("text", "*" + label + "* build #" + buildId + " *BAKE FAILURE*");

    ObjectNode reasonBlock = blocks.addObject();
    reasonBlock.put("type", "section");
    ObjectNode reasonText = reasonBlock.putObject("text");
    reasonText.put("type", "mrkdwn");
    reasonText.put("text", "```" + failureReason + "```");
    return body;
  }

  /**
   * Fire every stage-level hook on a stage that has reached its terminal status. Used when stage
   * notify is wired (follow-up beyond #245's build-level acceptance).
   */
  public void fireStageHooks(
      long buildId,
      @NonNull String stageName,
      @NonNull String result,
      @NonNull List<NotifyHook> hooks) {
    fireMany(hooks, result, buildId, /* jobName */ null, stageName);
  }

  private void fireMany(
      @NonNull List<NotifyHook> hooks,
      @NonNull String result,
      long buildId,
      @Nullable String jobName,
      @Nullable String stageName) {
    if (hooks.isEmpty()) {
      return;
    }
    boolean failed = "FAILED".equals(result);
    for (NotifyHook hook : hooks) {
      try {
        if (!matches(hook, failed)) {
          continue;
        }
        fireOne(hook, buildId, result, jobName, stageName);
      } catch (RuntimeException e) {
        // Defensive: notification MUST NOT abort the build's terminal write. We log type +
        // credentialsId reference (NOT the resolved secret) so an operator can correlate.
        LOGGER.log(
            Level.WARNING,
            "[titan] notify hook fire failed (build " + buildId + ", type=" + hook.getType() + ")",
            e);
      }
    }
  }

  private static boolean matches(@NonNull NotifyHook hook, boolean failed) {
    return failed ? hook.firesOnFailure() : hook.firesOnSuccess();
  }

  private void fireOne(
      @NonNull NotifyHook hook,
      long buildId,
      @NonNull String result,
      @Nullable String jobName,
      @Nullable String stageName) {
    switch (hook.getType()) {
      case "webhook" -> fireWebhook(hook, buildId, result, stageName);
      case "slack" -> fireSlack(hook, buildId, result, jobName, stageName);
      default ->
          // The parser rejects unsupported types; defence-in-depth.
          LOGGER.log(
              Level.WARNING,
              "[titan] notify: skipping unsupported hook type ''{0}''",
              new Object[] {hook.getType()});
    }
  }

  private void fireWebhook(
      @NonNull NotifyHook hook, long buildId, @NonNull String result, @Nullable String stageName) {
    String url = hook.getUrl();
    if (url == null || url.isBlank()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: webhook hook with no url — skipping (build {0})",
          buildId);
      return;
    }
    ObjectNode body = JSON.createObjectNode();
    body.put("buildId", buildId);
    body.put("status", result);
    if (stageName != null) {
      body.put("stage", stageName);
    }
    postJson(buildId, url, body, "webhook");
  }

  /**
   * Fire a Slack inbound-webhook (#358). Resolves the webhook URL from {@link CredentialsService}
   * by {@code credentialsId} (scope/key tuple, mirroring {@code CredentialResolver}'s addressing
   * convention) at dispatch time, uses it once, and lets the reference drop. The resolved URL is
   * NEVER logged and NEVER cached on the dispatcher — CONSTITUTION §6.
   */
  private void fireSlack(
      @NonNull NotifyHook hook,
      long buildId,
      @NonNull String result,
      @Nullable String jobName,
      @Nullable String stageName) {
    String credentialsId = hook.getCredentialsId();
    if (credentialsId == null || credentialsId.isBlank()) {
      // The parser enforces this; defence-in-depth.
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: slack hook with no credentialsId — skipping (build {0})",
          buildId);
      return;
    }
    if (credentials == null) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: slack hook on build {0} not dispatched — no CredentialsService is "
              + "wired (credentialsId=''{1}''); skipping (contract: best-effort, never throw)",
          new Object[] {buildId, credentialsId});
      return;
    }

    // Resolve at dispatch time, use, discard. NOT cached. NOT logged. CONSTITUTION §6.
    ScopedKey ref = parseCredentialsId(credentialsId);
    Optional<String> resolved;
    try {
      resolved = credentials.resolvePlaintext(ref.scope, ref.key);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: slack credential resolution failed (build "
              + buildId
              + ", credentialsId="
              + credentialsId
              + ")",
          e);
      return;
    }
    if (resolved.isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: slack credential ''{0}'' not found / empty — skipping (build {1})",
          new Object[] {credentialsId, buildId});
      return;
    }

    String label = jobName != null ? jobName : ("build " + buildId);
    if (stageName != null) {
      label = label + " · " + stageName;
    }
    ObjectNode body = buildSlackPayload(label, buildId, result, hook.getChannel());

    // The URL string is scoped to this method; we do not retain it past postJson().
    String url = resolved.get();
    postJson(buildId, url, body, "slack");
    // Best-effort hint to the JVM that this reference can drop. Java strings aren't zeroable, but
    // we make sure no field on `this` ever held it.
  }

  @NonNull
  private static ObjectNode buildSlackPayload(
      @NonNull String label, long buildId, @NonNull String result, @Nullable String channel) {
    ObjectNode body = JSON.createObjectNode();
    body.put("text", label + " — " + result);
    if (channel != null && !channel.isBlank()) {
      body.put("channel", channel);
    }
    ArrayNode blocks = body.putArray("blocks");

    ObjectNode section = blocks.addObject();
    section.put("type", "section");
    ObjectNode sectionText = section.putObject("text");
    sectionText.put("type", "mrkdwn");
    sectionText.put("text", "*" + label + "* build #" + buildId + " *" + result + "*");

    ObjectNode context = blocks.addObject();
    context.put("type", "context");
    ArrayNode elements = context.putArray("elements");
    ObjectNode element = elements.addObject();
    element.put("type", "mrkdwn");
    element.put("text", "build #" + buildId + " · status " + result);
    return body;
  }

  private void postJson(
      long buildId, @NonNull String url, @NonNull ObjectNode body, @NonNull String kindForLog) {
    String payload;
    try {
      payload = JSON.writeValueAsString(body);
    } catch (Exception e) {
      LOGGER.log(
          Level.WARNING, "[titan] notify: could not serialise " + kindForLog + " payload", e);
      return;
    }

    HttpRequest.Builder req;
    try {
      req =
          HttpRequest.newBuilder()
              .uri(URI.create(url))
              .timeout(HTTP_TIMEOUT)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload));
    } catch (IllegalArgumentException e) {
      // NOTE: for slack we deliberately do NOT log `url` (it carries a workspace token).
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: invalid " + kindForLog + " URL — skipping (build " + buildId + ")",
          e);
      return;
    }

    try {
      HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
        LOGGER.log(
            Level.FINE,
            "[titan] notify: {0} delivered (build {1}, status {2})",
            new Object[] {kindForLog, buildId, resp.statusCode()});
      } else {
        // For slack: log status code but NOT the URL.
        LOGGER.log(
            Level.WARNING,
            "[titan] notify: {0} returned {1} for build {2}",
            new Object[] {kindForLog, resp.statusCode(), buildId});
      }
    } catch (java.io.IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: " + kindForLog + " delivery failed (build " + buildId + ")",
          e);
    }
  }

  // ── #1102 rich-context dispatch ─────────────────────────────────────────

  /**
   * Visible for tests: iterate the hook list once with the recovery-aware predicate and dispatch
   * each match. Same fire-and-forget contract as {@link #fireMany}: never throws, every delivery
   * error is logged at WARNING and swallowed.
   */
  void fireManyRich(@NonNull List<NotifyHook> hooks, @NonNull NotificationContext ctx) {
    if (hooks.isEmpty()) {
      return;
    }
    for (NotifyHook hook : hooks) {
      try {
        if (!matchesRich(hook, ctx)) {
          continue;
        }
        fireOneRich(hook, ctx);
      } catch (RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "[titan] notify hook fire failed (build "
                + ctx.buildId()
                + ", type="
                + hook.getType()
                + ")",
            e);
      }
    }
  }

  /**
   * Recovery-aware predicate (#1102). A {@code SUCCESS} build matches a hook iff:
   *
   * <ul>
   *   <li>the build is a recovery AND the hook opted into {@code recovery} (or {@code always} /
   *       empty), OR
   *   <li>the hook opted into {@code success} (or {@code always} / empty).
   * </ul>
   *
   * A {@code FAILED} build uses {@link NotifyHook#firesOnFailure()} unchanged. Visible for tests.
   */
  static boolean matchesRich(@NonNull NotifyHook hook, @NonNull NotificationContext ctx) {
    if ("FAILED".equals(ctx.result())) {
      return hook.firesOnFailure();
    }
    if ("SUCCESS".equals(ctx.result())) {
      if (ctx.recovery() && hook.firesOnRecovery()) {
        return true;
      }
      return hook.firesOnSuccess();
    }
    // Other terminal labels (ABORTED, UNSTABLE): conservative — only fire `always`.
    return hook.getOn().contains("always");
  }

  private void fireOneRich(@NonNull NotifyHook hook, @NonNull NotificationContext ctx) {
    switch (hook.getType()) {
      case "webhook" -> fireWebhookRich(hook, ctx);
      case "slack" -> fireSlackRich(hook, ctx);
      default ->
          LOGGER.log(
              Level.WARNING,
              "[titan] notify: skipping unsupported hook type ''{0}''",
              new Object[] {hook.getType()});
    }
  }

  private void fireWebhookRich(@NonNull NotifyHook hook, @NonNull NotificationContext ctx) {
    String url = hook.getUrl();
    if (url == null || url.isBlank()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: webhook hook with no url — skipping (build {0})",
          ctx.buildId());
      return;
    }
    ObjectNode body = buildWebhookPayload(ctx);
    postJson(ctx.buildId(), url, body, "webhook");
  }

  private void fireSlackRich(@NonNull NotifyHook hook, @NonNull NotificationContext ctx) {
    String credentialsId = hook.getCredentialsId();
    if (credentialsId == null || credentialsId.isBlank()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: slack hook with no credentialsId — skipping (build {0})",
          ctx.buildId());
      return;
    }
    if (credentials == null) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: slack hook on build {0} not dispatched — no CredentialsService is "
              + "wired (credentialsId=''{1}''); skipping",
          new Object[] {ctx.buildId(), credentialsId});
      return;
    }
    ScopedKey ref = parseCredentialsId(credentialsId);
    Optional<String> resolved;
    try {
      resolved = credentials.resolvePlaintext(ref.scope, ref.key);
    } catch (RuntimeException e) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: slack credential resolution failed (build "
              + ctx.buildId()
              + ", credentialsId="
              + credentialsId
              + ")",
          e);
      return;
    }
    if (resolved.isEmpty()) {
      LOGGER.log(
          Level.WARNING,
          "[titan] notify: slack credential ''{0}'' not found / empty — skipping (build {1})",
          new Object[] {credentialsId, ctx.buildId()});
      return;
    }
    ObjectNode body = buildSlackPayloadRich(hook.getChannel(), ctx);
    String url = resolved.get();
    postJson(ctx.buildId(), url, body, "slack");
  }

  /**
   * Build the rich webhook JSON envelope (#1102). All fields are nullable-aware — a missing jobName
   * / durationMs / failStage / deepLink simply omits its key. Never contains a resolved Slack URL
   * (the {@code url} field on the wire is the webhook's own POST target, which is the user-supplied
   * inline URL — that's already by construction safe to surface to itself).
   *
   * <p>{@code kind} is a discriminated-union tag the receiver can switch on without sniffing other
   * fields:
   *
   * <ul>
   *   <li>{@code BUILD_FAILED} — terminal {@code FAILED} (any prior state).
   *   <li>{@code BUILD_FIRST_FAILURE} — terminal {@code FAILED} when the previous finished build
   *       was {@code SUCCESS} (the "first-failure-after-N-passes" test-matrix case).
   *   <li>{@code BUILD_RECOVERED} — terminal {@code SUCCESS} on a fail→success transition.
   *   <li>{@code BUILD_SUCCEEDED} — terminal {@code SUCCESS}, all other cases.
   * </ul>
   *
   * Visible for tests.
   */
  @NonNull
  static ObjectNode buildWebhookPayload(@NonNull NotificationContext ctx) {
    ObjectNode body = JSON.createObjectNode();
    body.put("kind", payloadKind(ctx));
    body.put("buildId", ctx.buildId());
    body.put("status", ctx.result());
    if (ctx.previousResult() != null) {
      body.put("previousStatus", ctx.previousResult());
    }
    if (ctx.jobName() != null) {
      body.put("jobName", ctx.jobName());
    }
    if (ctx.durationMs() != null) {
      body.put("durationMs", ctx.durationMs());
    }
    body.put("duration", ctx.renderDuration());
    if (ctx.failedStageName() != null) {
      body.put("failStage", ctx.failedStageName());
    }
    if (ctx.deepLink() != null) {
      body.put("url", ctx.deepLink());
    }
    return body;
  }

  /**
   * Build the rich Slack block-kit payload (#1102). Header line varies by {@link #payloadKind} so
   * an on-call channel can scan: 🔴 failure, 🟠 first failure after a clean run, 🟢 recovery, 🟢
   * green. The deep-link is rendered as a Slack action block ({@code <url|View build>}) so a
   * responder can click straight into the build-detail page.
   *
   * <p>Channel override (a plain display label like {@code #oncall}, NOT a secret) is passed
   * through unchanged.
   *
   * <p>Visible for tests.
   */
  @NonNull
  static ObjectNode buildSlackPayloadRich(
      @Nullable String channel, @NonNull NotificationContext ctx) {
    ObjectNode body = JSON.createObjectNode();
    String kind = payloadKind(ctx);
    String label = ctx.jobName() != null ? ctx.jobName() : ("build " + ctx.buildId());
    String emoji =
        switch (kind) {
          case "BUILD_FAILED" -> ":red_circle:";
          case "BUILD_FIRST_FAILURE" -> ":large_orange_circle:";
          case "BUILD_RECOVERED" -> ":large_green_circle:";
          default -> ":white_check_mark:";
        };
    String headline =
        switch (kind) {
          case "BUILD_FAILED" -> "FAILED";
          case "BUILD_FIRST_FAILURE" -> "FAILED (first failure after passes)";
          case "BUILD_RECOVERED" -> "RECOVERED (was FAILED)";
          default -> "SUCCESS";
        };
    body.put("text", emoji + " " + label + " #" + ctx.buildId() + " — " + headline);
    if (channel != null && !channel.isBlank()) {
      body.put("channel", channel);
    }

    ArrayNode blocks = body.putArray("blocks");

    ObjectNode header = blocks.addObject();
    header.put("type", "section");
    ObjectNode headerText = header.putObject("text");
    headerText.put("type", "mrkdwn");
    headerText.put(
        "text", emoji + " *" + label + "* build #" + ctx.buildId() + " — *" + headline + "*");

    ObjectNode fields = blocks.addObject();
    fields.put("type", "section");
    ArrayNode fieldsArr = fields.putArray("fields");
    fieldsArr.addObject().put("type", "mrkdwn").put("text", "*Status*\n" + ctx.result());
    fieldsArr.addObject().put("type", "mrkdwn").put("text", "*Duration*\n" + ctx.renderDuration());
    if (ctx.failedStageName() != null) {
      fieldsArr
          .addObject()
          .put("type", "mrkdwn")
          .put("text", "*Failed stage*\n" + ctx.failedStageName());
    }
    if (ctx.previousResult() != null) {
      fieldsArr
          .addObject()
          .put("type", "mrkdwn")
          .put("text", "*Previous*\n" + ctx.previousResult());
    }

    if (ctx.deepLink() != null) {
      ObjectNode actions = blocks.addObject();
      actions.put("type", "actions");
      ArrayNode elements = actions.putArray("elements");
      ObjectNode button = elements.addObject();
      button.put("type", "button");
      ObjectNode btext = button.putObject("text");
      btext.put("type", "plain_text");
      btext.put("text", "View build");
      button.put("url", ctx.deepLink());
    }

    return body;
  }

  /**
   * Discriminator-tag for the rich payload (#1102). Visible for tests. See {@link
   * #buildWebhookPayload} for the four labels.
   */
  @NonNull
  static String payloadKind(@NonNull NotificationContext ctx) {
    if ("FAILED".equals(ctx.result())) {
      return ctx.firstFailureAfterPasses() ? "BUILD_FIRST_FAILURE" : "BUILD_FAILED";
    }
    if ("SUCCESS".equals(ctx.result())) {
      return ctx.recovery() ? "BUILD_RECOVERED" : "BUILD_SUCCEEDED";
    }
    return "BUILD_" + ctx.result();
  }

  /**
   * Parse a credentials id of the form {@code "scope/key"} into its addressing tuple, mirroring
   * {@code CredentialResolver#parseId}. A bare id (no slash) addresses the conventional {@code
   * "default"} scope.
   */
  @NonNull
  private static ScopedKey parseCredentialsId(@NonNull String id) {
    int slash = id.indexOf('/');
    if (slash < 0) {
      return new ScopedKey("default", id);
    }
    return new ScopedKey(id.substring(0, slash), id.substring(slash + 1));
  }

  private record ScopedKey(@NonNull String scope, @NonNull String key) {}
}
