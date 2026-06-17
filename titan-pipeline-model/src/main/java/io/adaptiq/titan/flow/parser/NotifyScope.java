package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.NotifyHook;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The {@code notify:} grammar scope (#245) — declarative lifecycle hooks fired by the orchestrator
 * at terminal state. Stage-only on the {@link StepScope} surface (a step has no terminal state of
 * its own); the pipeline-root form is parsed inline by {@link TitanYamlParser} using {@link
 * #parseList(JsonNode, String)} so this scope is the single source of grammar truth for both.
 *
 * <p>The value is a list of {@link NotifyHook}: each entry is an object with {@code type:} ({@code
 * webhook} or {@code slack}), {@code on:} (a list of {@code success}/{@code failure}/{@code
 * always}), and either an inline {@code url:} (webhook only) or a {@code credentialsId:} (slack —
 * the resolved secret IS the workspace-token-bearing webhook URL; design/39, CONSTITUTION §6).
 *
 * <p>Slack hooks (#358) require {@code credentialsId:} and FORBID inline {@code url:}. Routing
 * decisions are made purely on the {@code type:} discriminator — we do NOT inspect URL values to
 * "detect" misuse (no regex on user-supplied URLs; that's runtime URL-sniffing and is explicitly
 * not the contract — design/58).
 */
final class NotifyScope implements StepScope {

  static final String KEY = "notify";

  /** Keys allowed inside a single notify-list entry. */
  private static final Set<String> HOOK_KEYS =
      Set.of("type", "on", "url", "credentialsId", "channel");

  /**
   * Allowed values for the {@code on:} predicate list.
   *
   * <ul>
   *   <li>{@code success} — terminal status {@code SUCCESS}.
   *   <li>{@code failure} — terminal status {@code FAILED}.
   *   <li>{@code recovery} — terminal {@code SUCCESS} when the previous finished build of the same
   *       job was {@code FAILED} (#1102 — the fail→success transition; e.g. on-call should hear
   *       about the recovery, not every green build). Independent of {@code success}: a hook
   *       declared {@code on: [recovery]} fires ONLY on the transition.
   *   <li>{@code always} — fires regardless of result.
   * </ul>
   */
  private static final Set<String> ON_VALUES = Set.of("success", "failure", "recovery", "always");

  /** Allowed values for the {@code type:} discriminator. */
  private static final Set<String> SUPPORTED_TYPES = Set.of("webhook", "slack");

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Lifecycle hooks fired by the orchestrator when this stage reaches its terminal "
            + "state, regardless of which step ran (#245). Each entry is a webhook (today) "
            + "or slack (future) sink; auth tokens MUST travel via 'credentialsId', never "
            + "inline in 'url'.",
        GrammarKey.arrayOfRef("notifyHook"));
  }

  @Override
  public boolean appliesToStep() {
    return false; // a step has no terminal-state lifecycle of its own.
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    // notify: is pipeline/stage-only — nothing to do on a step.
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    JsonNode node = stageNode.get(KEY);
    if (node == null) {
      return;
    }
    ctx.stage().setNotify(parseList(node, ctx.location() + " notify"));
  }

  /**
   * Parse a {@code notify:} list — on a pipeline root or a stage — into a list of {@link
   * NotifyHook}. {@link TitanYamlParser} calls this directly for the pipeline-root form so the
   * grammar lives in one place. A present-but-null or non-array value is a located error.
   */
  @NonNull
  static List<NotifyHook> parseList(@Nullable JsonNode node, @NonNull String context) {
    List<NotifyHook> out = new ArrayList<>();
    if (node == null) {
      return out;
    }
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull(KEY, context);
    }
    if (!node.isArray()) {
      throw new PipelineParseException(
          context
              + ": 'notify' must be an array of hook objects, got "
              + TypedNodeReader.describe(node));
    }
    int index = 0;
    for (JsonNode entry : node) {
      String where = context + "[" + index + "]";
      out.add(parseHook(entry, where));
      index++;
    }
    return out;
  }

  @NonNull
  private static NotifyHook parseHook(@NonNull JsonNode entry, @NonNull String where) {
    if (entry.isNull() || !entry.isObject()) {
      throw new PipelineParseException(
          where
              + ": each 'notify' entry must be an object, got "
              + TypedNodeReader.describe(entry));
    }
    ParseSupport.rejectUnknownKeys(entry, HOOK_KEYS, where);

    JsonNode typeNode = entry.get("type");
    if (typeNode == null) {
      throw new PipelineParseException(where + ": 'type' is required (e.g. 'webhook')");
    }
    if (typeNode.isNull()) {
      throw TypedNodeReader.presentButNull("type", where);
    }
    if (!typeNode.isTextual()) {
      throw new PipelineParseException(
          where + ": 'type' must be a string, got " + TypedNodeReader.describe(typeNode));
    }
    String type = typeNode.asText();
    if (!SUPPORTED_TYPES.contains(type)) {
      throw new PipelineParseException(
          where + ": unknown 'type' '" + type + "' — supported: " + SUPPORTED_TYPES);
    }

    // on:
    List<String> on = parseOnList(entry.get("on"), where);

    // credentialsId: optional for webhook, REQUIRED for slack (#358). Validated as a string id —
    // its resolution is a runtime concern of the dispatcher, not the parser. NEVER store the
    // secret value itself here (CONSTITUTION §6 — NO plaintext secret in config_json).
    String credentialsId = null;
    JsonNode credNode = entry.get("credentialsId");
    if (credNode != null && !credNode.isNull()) {
      if (!credNode.isTextual() || credNode.asText().isBlank()) {
        throw new PipelineParseException(
            where
                + ": 'credentialsId' must be a non-blank string (a CredentialsService id), got "
                + TypedNodeReader.describe(credNode));
      }
      credentialsId = credNode.asText();
    }

    // url: required for webhook; FORBIDDEN for slack (the URL IS the secret — it must come from
    // CredentialsService at dispatch time, never live in config_json — CONSTITUTION §6).
    JsonNode urlNode = entry.get("url");
    String url = null;
    if ("webhook".equals(type)) {
      if (urlNode == null) {
        throw new PipelineParseException(
            where + ": 'url' is required for a 'webhook' hook (the POST target)");
      }
      if (urlNode.isNull()) {
        throw TypedNodeReader.presentButNull("url", where);
      }
      if (!urlNode.isTextual() || urlNode.asText().isBlank()) {
        throw new PipelineParseException(
            where + ": 'url' must be a non-blank string, got " + TypedNodeReader.describe(urlNode));
      }
      url = urlNode.asText();
      // Note: we deliberately do NOT sniff the URL value to "detect" whether it's really a Slack
      // webhook in disguise. The schema's `type:` discriminator IS the contract — if the user
      // writes `type: webhook`, this branch accepts the URL as-is (#358 design correction: no
      // runtime URL-shape inference; use `type: slack` + `credentialsId:` for routed secrets).
    } else if ("slack".equals(type)) {
      if (urlNode != null) {
        throw new PipelineParseException(
            where
                + ": 'url' is not allowed for 'type: slack' — the Slack inbound-webhook URL "
                + "carries a workspace token and must come from CredentialsService at dispatch "
                + "time via 'credentialsId', never inline in config_json (#358).");
      }
      if (credentialsId == null) {
        throw new PipelineParseException(
            where
                + ": 'credentialsId' is required for 'type: slack' — the Slack inbound-webhook "
                + "URL is a secret and must be resolved from CredentialsService at dispatch "
                + "time, never inline (CONSTITUTION §6, #358).");
      }
    }

    // channel: optional, slack-only display label. Plain text, not a secret.
    String channel = null;
    JsonNode chNode = entry.get("channel");
    if (chNode != null && !chNode.isNull()) {
      if (!"slack".equals(type)) {
        throw new PipelineParseException(
            where + ": 'channel' is only valid on 'type: slack' hooks");
      }
      if (!chNode.isTextual() || chNode.asText().isBlank()) {
        throw new PipelineParseException(
            where
                + ": 'channel' must be a non-blank string, got "
                + TypedNodeReader.describe(chNode));
      }
      channel = chNode.asText();
    }

    NotifyHook hook = new NotifyHook();
    hook.setType(type);
    hook.setOn(on);
    hook.setUrl(url);
    hook.setCredentialsId(credentialsId);
    hook.setChannel(channel);
    return hook;
  }

  @NonNull
  private static List<String> parseOnList(@Nullable JsonNode node, @NonNull String where) {
    List<String> out = new ArrayList<>();
    if (node == null) {
      return out;
    }
    if (node.isNull()) {
      throw TypedNodeReader.presentButNull("on", where);
    }
    if (!node.isArray()) {
      throw new PipelineParseException(
          where
              + ": 'on' must be an array of "
              + ON_VALUES
              + ", got "
              + TypedNodeReader.describe(node));
    }
    int i = 0;
    for (JsonNode item : node) {
      if (item.isNull() || !item.isTextual()) {
        throw new PipelineParseException(
            where + ": 'on[" + i + "]' must be a string, got " + TypedNodeReader.describe(item));
      }
      String v = item.asText();
      if (!ON_VALUES.contains(v)) {
        throw new PipelineParseException(
            where + ": 'on[" + i + "]' must be one of " + ON_VALUES + ", got '" + v + "'");
      }
      out.add(v);
      i++;
    }
    return out;
  }
}
