package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.TriggerModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import io.adaptiq.titan.flow.parser.grammar.TitanGrammar;
import io.adaptiq.titan.flow.parser.grammar.TriggerGrammar;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Parses the optional {@code triggers:} block (design/50 D7) into {@link TriggerModel}s — extracted
 * from {@link TitanYamlParser} (design/59) so the parser stays within its size budget.
 *
 * <p>A trigger entry is a discriminated union (issues #397, #1078, #1079): exactly one of {@code
 * cron:}, {@code github:}, {@code gitlab:} or {@code bitbucket:}. The three webhook discriminators
 * share the same inner shape — {@code branches} / {@code events} (optional string-or-list) and a
 * required {@code credentialsId} — so they flow through one {@link #readWebhookTrigger} helper.
 */
final class TriggerModelParser {

  // design/47 §4.1: key sets projected from the single in-code grammar declaration. Package-private
  // so TitanYamlParser#grammarKeySetsForTest can expose them to the schema-contract test.
  static final Set<String> TRIGGER_KEYS = TitanGrammar.keyNames(TriggerGrammar.TRIGGER);
  static final Set<String> GITHUB_TRIGGER_KEYS =
      TitanGrammar.keyNames(TriggerGrammar.GITHUB_TRIGGER);
  static final Set<String> GITLAB_TRIGGER_KEYS =
      TitanGrammar.keyNames(TriggerGrammar.GITLAB_TRIGGER);
  static final Set<String> BITBUCKET_TRIGGER_KEYS =
      TitanGrammar.keyNames(TriggerGrammar.BITBUCKET_TRIGGER);

  private TriggerModelParser() {}

  /** Parse the optional {@code triggers:} block — the schedules that start a build (design/50). */
  static void parse(
      @NonNull JsonNode titan, @NonNull PipelineModel model, @NonNull String context) {
    // design/48 D2: `triggers` is a grammar key declared `array` — read it through the typed
    // reader so `triggers: null` is a present-and-null error and `triggers: {a:1}` a type error.
    JsonNode triggers =
        TypedNodeReader.optionalNode(
            titan, TitanGrammar.key(TitanGrammar.ROOT, "triggers"), context);
    if (triggers == null) {
      return;
    }
    int index = 0;
    for (JsonNode t : triggers) {
      String where = "triggers[" + index + "]";
      if (!t.isObject()) {
        throw new PipelineParseException(where + " must be an object");
      }
      ParseSupport.rejectUnknownKeys(t, TRIGGER_KEYS, where);

      // Discriminated union (issues #397, #1078, #1079): exactly one of `cron:`, `github:`,
      // `gitlab:` or `bitbucket:` must be present.
      boolean hasCron = t.has("cron");
      boolean hasGithub = t.has("github");
      boolean hasGitlab = t.has("gitlab");
      boolean hasBitbucket = t.has("bitbucket");
      int discriminators =
          (hasCron ? 1 : 0) + (hasGithub ? 1 : 0) + (hasGitlab ? 1 : 0) + (hasBitbucket ? 1 : 0);
      if (discriminators == 0) {
        throw new PipelineParseException(
            where
                + " must have one of 'cron', 'github', 'gitlab' or 'bitbucket' "
                + "(issues #397, #1078, #1079)");
      }
      if (discriminators > 1) {
        throw new PipelineParseException(
            where
                + " must have exactly one of 'cron', 'github', 'gitlab' or 'bitbucket' — not more");
      }

      if (hasCron) {
        String cron =
            TypedNodeReader.requireString(
                t, TitanGrammar.key(TriggerGrammar.TRIGGER, "cron"), where);
        model.getTriggers().add(new TriggerModel(cron));
      } else if (hasGithub) {
        model.getTriggers().add(new TriggerModel(parseGithubTrigger(t.get("github"), where)));
      } else if (hasGitlab) {
        model.getTriggers().add(new TriggerModel(parseGitlabTrigger(t.get("gitlab"), where)));
      } else {
        model.getTriggers().add(new TriggerModel(parseBitbucketTrigger(t.get("bitbucket"), where)));
      }
      index++;
    }
  }

  /** Parse the {@code github:} trigger discriminator's inner object (issue #397). */
  @NonNull
  private static TriggerModel.GithubTriggerModel parseGithubTrigger(
      @NonNull JsonNode node, @NonNull String where) {
    TriggerModel.GithubTriggerModel out = new TriggerModel.GithubTriggerModel();
    readWebhookTrigger(
        node,
        where,
        "github",
        GITHUB_TRIGGER_KEYS,
        TriggerGrammar.GITHUB_TRIGGER,
        out::setBranches,
        out::setEvents,
        out::setCredentialsId);
    return out;
  }

  /** Parse the {@code gitlab:} trigger discriminator's inner object (issue #1078). */
  @NonNull
  private static TriggerModel.GitlabTriggerModel parseGitlabTrigger(
      @NonNull JsonNode node, @NonNull String where) {
    TriggerModel.GitlabTriggerModel out = new TriggerModel.GitlabTriggerModel();
    readWebhookTrigger(
        node,
        where,
        "gitlab",
        GITLAB_TRIGGER_KEYS,
        TriggerGrammar.GITLAB_TRIGGER,
        out::setBranches,
        out::setEvents,
        out::setCredentialsId);
    return out;
  }

  /** Parse the {@code bitbucket:} trigger discriminator's inner object (issue #1079). */
  @NonNull
  private static TriggerModel.BitbucketTriggerModel parseBitbucketTrigger(
      @NonNull JsonNode node, @NonNull String where) {
    TriggerModel.BitbucketTriggerModel out = new TriggerModel.BitbucketTriggerModel();
    readWebhookTrigger(
        node,
        where,
        "bitbucket",
        BITBUCKET_TRIGGER_KEYS,
        TriggerGrammar.BITBUCKET_TRIGGER,
        out::setBranches,
        out::setEvents,
        out::setCredentialsId);
    return out;
  }

  /**
   * Shared shape for the github/gitlab/bitbucket webhook trigger discriminators (issues #397,
   * #1078, #1079): the value must be a non-null object, unknown keys are rejected, {@code
   * credentialsId} is required, and {@code branches} / {@code events} are optional string-or-list
   * shapes. The vendor's typed model is populated via the supplied setters.
   */
  private static void readWebhookTrigger(
      @NonNull JsonNode node,
      @NonNull String where,
      @NonNull String discriminator,
      @NonNull Set<String> keys,
      @NonNull List<GrammarKey> ctx,
      @NonNull Consumer<List<String>> setBranches,
      @NonNull Consumer<List<String>> setEvents,
      @NonNull Consumer<String> setCredentialsId) {
    String childCtx = where + "." + discriminator;
    if (node.isNull()) {
      throw new PipelineParseException(childCtx + " is present but null — expected an object");
    }
    if (!node.isObject()) {
      throw new PipelineParseException(childCtx + " must be an object");
    }
    ParseSupport.rejectUnknownKeys(node, keys, childCtx);
    setBranches.accept(
        TypedNodeReader.stringList(node, TitanGrammar.key(ctx, "branches"), childCtx));
    setEvents.accept(TypedNodeReader.stringList(node, TitanGrammar.key(ctx, "events"), childCtx));
    setCredentialsId.accept(
        TypedNodeReader.requireString(node, TitanGrammar.key(ctx, "credentialsId"), childCtx));
  }
}
