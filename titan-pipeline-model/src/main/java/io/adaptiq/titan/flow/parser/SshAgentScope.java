package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import io.adaptiq.titan.flow.parser.grammar.GrammarType;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code sshAgent:} grammar scope (design/41 §2, design/42 §4.7) — SSH-agent key ids scoped to
 * a step or a stage.
 *
 * <p>A stage-level {@code sshAgent:} list is parser sugar: it is flattened onto every step in the
 * stage, de-duplicated, the stage's ids first and the step's own ids appended after. The {@code
 * sh}-only restriction (design/41 §2.1) is enforced on the <em>effective</em> (post-flatten) list,
 * so a stage-level {@code sshAgent:} landing on a non-{@code sh} step is caught too.
 */
final class SshAgentScope implements StepScope {

  static final String KEY = "sshAgent";

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "SSH-agent credential ids (each an SSHUserPrivateKey) loaded for every sh/script "
            + "step of this stage (design/41). Flattened onto each step at parse "
            + "time, de-duplicated; a step's own 'sshAgent' ids are appended after "
            + "these.",
        GrammarKey.ref("stringOrList"));
  }

  @Override
  public GrammarKey stepSchema() {
    return GrammarKey.optional(
        KEY,
        "SSH-agent credential ids (each an SSHUserPrivateKey) loaded into a per-step "
            + "ssh-agent for the step's shell process (design/41). Valid on 'sh' and "
            + "'script' steps only; rejected on any other descriptor. Resolved on the "
            + "controller at dispatch.",
        GrammarKey.ref("stringOrList"));
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    step.setSshAgent(parse(stepNode.get(KEY), ctx.location()));
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    List<String> stageIds = parse(stageNode.get(KEY), ctx.location());
    for (StepModel step : steps) {
      // The step's own ids, captured before the stage-level list is flattened in — so a
      // sh-only rejection can name whether the offending ids are step-level or flattened.
      boolean stepLevel = !step.getSshAgent().isEmpty();
      step.setSshAgent(merge(stageIds, step.getSshAgent()));
      // `sshAgent:` is honoured on `sh` steps only (design/41 §2.1): a `script` step's body
      // is in-process Groovy, so the worker cannot wrap a single shell invocation for it.
      // The check runs on the effective list, so a stage-level `sshAgent:` landing on a
      // non-`sh` step (e.g. `git`) is caught too.
      if (!step.getSshAgent().isEmpty() && !"sh".equals(step.getDescriptorId())) {
        throw new PipelineParseException(
            ctx.location()
                + " step "
                + step.getId()
                + ": 'sshAgent' is only valid on "
                + "'sh' steps (design/41 §2.1) — found it on a '"
                + step.getDescriptorId()
                + "' step ("
                + (stepLevel
                    ? "step-level 'sshAgent:'"
                    : "flattened from a stage-level 'sshAgent:'")
                + ")");
      }
    }
  }

  /**
   * Parse an {@code sshAgent:} node — on a step or a stage. The value is a list of credential-store
   * ids (each naming an SSH private key); a bare scalar is accepted as a one-element list. The
   * model carries the ids only — no key material. Ids are de-duplicated with order preserved; a
   * blank id is rejected with a located error.
   */
  @NonNull
  static List<String> parse(@Nullable JsonNode node, @NonNull String context) {
    List<String> out = new ArrayList<>();
    if (node == null) {
      return out; // genuinely absent — the key is not in the mapping.
    }
    // design/48 D2/D3/D4: `sshAgent` is declared string-or-list — a present-and-null value is
    // an error, and a wrong-typed value (a number, an object) fails with a located type error.
    TypedNodeReader.requireTyped(node, KEY, GrammarType.STRING_OR_LIST, context);
    if (node.isArray()) {
      int index = 0;
      for (JsonNode item : node) {
        String where = context + " sshAgent[" + index + "]";
        // The element type is enforced here: a string-or-list's elements are strings.
        if (!item.isTextual() || item.textValue().isBlank()) {
          throw new PipelineParseException(
              where + ": each 'sshAgent' entry must be a non-blank credential id");
        }
        String id = item.textValue();
        if (!out.contains(id)) {
          out.add(id);
        }
        index++;
      }
    } else {
      // a scalar shorthand, e.g. `sshAgent: prod-deploy-key`.
      if (node.textValue().isBlank()) {
        throw new PipelineParseException(
            context + ": 'sshAgent' must be a non-blank credential id");
      }
      out.add(node.textValue());
    }
    return out;
  }

  /**
   * Flatten a stage-level {@code sshAgent:} list onto a step: the stage's ids first, the step's own
   * ids appended after, de-duplicated with order preserved.
   */
  @NonNull
  private static List<String> merge(@NonNull List<String> stageIds, @NonNull List<String> stepIds) {
    List<String> out = new ArrayList<>();
    for (String id : stageIds) {
      if (!out.contains(id)) {
        out.add(id);
      }
    }
    for (String id : stepIds) {
      if (!out.contains(id)) {
        out.add(id);
      }
    }
    return out;
  }
}
