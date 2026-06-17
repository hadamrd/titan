package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.CredentialBinding;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import io.adaptiq.titan.flow.parser.grammar.GrammarType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The {@code credentials:} grammar scope (design/39 §2, design/42 §4.7) — stored-credential secret
 * bindings scoped to a step or a stage.
 *
 * <p>A stage-level {@code credentials:} list is parser sugar: it is flattened onto every step in
 * the stage so the baked DAG carries only step-scoped bindings. The effective per-step set is
 * {@code stage ++ step}; the pipeline-level prefix is prepended by {@link TitanYamlParser} so the
 * full order is pipeline {@code >} stage {@code >} step (later wins a variable-name clash).
 */
final class CredentialsScope implements StepScope {

  static final String KEY = "credentials";

  /**
   * The keys allowed on one {@code credentials:} entry (design/39 §2). {@code id} + {@code type}
   * are required; the rest are the type-specific binding-variable names. This is the union over all
   * credential types — the per-type binding-key requirements are enforced by the controller's
   * resolver, so a new credential type does not need a parser change.
   */
  private static final Set<String> CREDENTIAL_KEYS =
      Set.of(
          "id",
          "type",
          "usernameVariable",
          "passwordVariable",
          "variable",
          "keyFileVariable",
          "passphraseVariable");

  /** The binding-variable keys — everything in an entry that is not id/type. */
  private static final Set<String> CREDENTIAL_BINDING_KEYS =
      Set.of(
          "usernameVariable",
          "passwordVariable",
          "variable",
          "keyFileVariable",
          "passphraseVariable");

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    // The stage-level declaration (design/39). The pipeline-level credentials: list reuses the
    // same value shape; the root description (design/42) is declared in TitanGrammar.ROOT.
    return GrammarKey.optional(
        KEY,
        "Stored credentials bound into every step of this stage (design/39). "
            + "Flattened onto each step at parse time; a step's own 'credentials' are "
            + "appended after these.",
        GrammarKey.arrayOfRef("credentialBinding"));
  }

  @Override
  public GrammarKey stepSchema() {
    return GrammarKey.optional(
        KEY,
        "Stored credentials bound into this step as masked, step-scoped "
            + "environment (design/39 / D6). Resolved on the controller at dispatch; "
            + "the worker never sees the credentials store.",
        GrammarKey.arrayOfRef("credentialBinding"));
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    step.setCredentials(parse(stepNode.get(KEY), ctx.location()));
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    List<CredentialBinding> stageCreds = parse(stageNode.get(KEY), ctx.location());
    for (StepModel step : steps) {
      List<CredentialBinding> merged = new ArrayList<>(stageCreds);
      merged.addAll(step.getCredentials());
      step.setCredentials(merged);
    }
  }

  /**
   * Parse a {@code credentials:} list — on a step or a stage. Each entry is a map with a required
   * {@code id} (a credential-store id) and {@code type}, plus type-specific binding-variable keys.
   * The model carries the id + the binding shape only — no secret; resolution happens on the
   * controller at dispatch (design/39 §3). A {@code null} node (the key absent) is an empty list.
   */
  @NonNull
  static List<CredentialBinding> parse(@Nullable JsonNode node, @NonNull String context) {
    List<CredentialBinding> out = new ArrayList<>();
    if (node == null) {
      return out; // genuinely absent — the key is not in the mapping.
    }
    // design/48 D2/D3/D4: `credentials` is declared an array — a present-and-null value is an
    // error (`credentials: null` no longer silently means an empty list), and a non-array
    // value fails with a located type error rather than the old hand-rolled message.
    TypedNodeReader.requireTyped(node, KEY, GrammarType.ARRAY, context);
    // design/42 §3: two bindings in one credentials list that bind the same env variable is
    // unambiguously a mistake — fail closed, located. Cross-scope shadowing (step over stage
    // over pipeline) is legitimate and is left to the merge order; this guard is intra-list.
    Set<String> boundVars = new HashSet<>();
    int index = 0;
    for (JsonNode entry : node) {
      String where = context + " credentials[" + index + "]";
      if (!entry.isObject()) {
        throw new PipelineParseException(where + " must be an object");
      }
      ParseSupport.rejectUnknownKeys(entry, CREDENTIAL_KEYS, where);
      CredentialBinding binding = new CredentialBinding();
      binding.setId(ParseSupport.requireText(entry, "id", where));
      String type = ParseSupport.requireText(entry, "type", where);
      if (!CredentialBinding.TYPE_USERNAME_PASSWORD.equals(type)
          && !CredentialBinding.TYPE_STRING.equals(type)
          && !CredentialBinding.TYPE_FILE.equals(type)
          && !CredentialBinding.TYPE_SSH_KEY.equals(type)) {
        throw new PipelineParseException(
            where
                + ": unknown credential type '"
                + type
                + "' (allowed: usernamePassword, "
                + "string, file, sshKey)");
      }
      binding.setType(type);
      for (String key : CREDENTIAL_BINDING_KEYS) {
        String varName = ParseSupport.optText(entry, key);
        if (varName != null && !varName.isBlank()) {
          binding.getBindings().put(key, varName);
        }
      }
      if (binding.getBindings().isEmpty()) {
        throw new PipelineParseException(
            where + ": a credential binding must name at least one binding variable");
      }
      for (String varName : binding.getBindings().values()) {
        if (!boundVars.add(varName)) {
          throw new PipelineParseException(
              where
                  + ": variable '"
                  + varName
                  + "' is bound by more than one credential"
                  + " in this list — each variable may be bound once");
        }
      }
      out.add(binding);
      index++;
    }
    return out;
  }
}
