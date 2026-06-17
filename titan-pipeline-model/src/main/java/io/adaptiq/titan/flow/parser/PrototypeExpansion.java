package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PreconditionModel;
import io.adaptiq.titan.flow.model.StageModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bake-time {@code dependsOn} rewrite for matrix/each fan-out (issue #398).
 *
 * <p>When a stage carries {@code matrix:} or {@code each:}, the parser fans it out into N cell
 * stages with suffixed names (e.g. {@code build [arch=amd64,jdk=17]}). The prototype name (e.g.
 * {@code build}) is no longer a node in the DAG. A downstream stage saying {@code dependsOn:
 * [build]} could not resolve — it had to spell every cell out by hand, leaking the fan-out cell-id
 * shape into the user pipeline.
 *
 * <p>This helper rewrites {@code dependsOn} at bake time: an entry that matches a recorded
 * prototype name is expanded to the full list of cell names (fan-in semantics — matches Buildkite,
 * GHA matrix, Bazel). A literal cell name is left untouched, so existing pipelines that already
 * spell out cells keep working.
 *
 * <p>The map is filled by {@link TitanYamlParser} as it expands each prototype, and applied to
 * every node ({@link StageModel}, {@link GateModel}, {@link PreconditionModel}) before {@link
 * PipelineDagValidator} runs — so DAG validation sees the resolved edges, not the prototype
 * reference.
 */
final class PrototypeExpansion {

  private final Map<String, List<String>> protoToCells = new LinkedHashMap<>();

  /**
   * Record that a prototype named {@code prototypeName} expanded into the given cell stages. The
   * cell-name order matches the parser's fan-out order, which is the order the DAG sees them.
   */
  void record(@NonNull String prototypeName, @NonNull List<StageModel> cells) {
    if (cells.isEmpty()) {
      return;
    }
    List<String> names = new ArrayList<>(cells.size());
    for (StageModel cell : cells) {
      names.add(cell.getName());
    }
    protoToCells.put(prototypeName, names);
  }

  /**
   * The set of prototype names this expansion has recorded — the pre-expansion stage names that
   * fanned out into cells (design/68 D3). Used by {@link #validateOnFailureTargets} to resolve
   * {@code onFailure:} references that cite a prototype name (the PDL contract is the user-written
   * name, not the post-expansion cell names).
   */
  @NonNull
  Set<String> prototypeNames() {
    return Collections.unmodifiableSet(protoToCells.keySet());
  }

  /**
   * Validate every stage's {@code onFailure:} targets (design/68 D4.2, #947): each target must
   * resolve to either a declared post-expansion stage name OR a recorded prototype name (the PDL
   * contract — design/68 D3). Self-reference and empty-list were rejected at parse time by {@code
   * OnFailureScope}; this is the cross-stage check that needs the full pipeline view.
   */
  void validateOnFailureTargets(@NonNull PipelineModel model) {
    Set<String> declared = new LinkedHashSet<>();
    for (StageModel s : model.getStages()) {
      declared.add(s.getName());
    }
    PipelineDagValidator.validateOnFailureTargets(model, declared, prototypeNames());
  }

  /**
   * Rewrite {@code dependsOn} on every node in {@code model}: any entry that matches a recorded
   * prototype name is replaced (in place, preserving order) with the prototype's cell names. A
   * literal cell name — or any other name — passes through unchanged. Duplicates introduced by
   * mixing a prototype reference with one of its own cells are de-duped.
   */
  void rewrite(@NonNull PipelineModel model) {
    if (protoToCells.isEmpty()) {
      return;
    }
    for (StageModel stage : model.getStages()) {
      stage.setDependsOn(expand(stage.getDependsOn()));
      // design/68 D3 / #947: an `onFailure:` reference to a prototype name must
      // resolve to ANY cell failing (any-cell-failed). Expanding to the full
      // cell list at bake time keeps the orchestrator's gate logic simple — it
      // just checks each listed upstream name's terminal status.
      if (!stage.getOnFailureStages().isEmpty()) {
        stage.setOnFailureStages(expand(stage.getOnFailureStages()));
      }
    }
    for (GateModel gate : model.getGates()) {
      gate.setDependsOn(expand(gate.getDependsOn()));
    }
    for (PreconditionModel pre : model.getPreconditions()) {
      pre.setDependsOn(expand(pre.getDependsOn()));
    }
  }

  @NonNull
  private List<String> expand(@NonNull List<String> deps) {
    if (deps.isEmpty()) {
      return deps;
    }
    // Use LinkedHashSet for order-preserving de-dup — mixing `dependsOn: [build, build-arch-amd64]`
    // shouldn't blow up the DAG with a duplicate edge.
    Set<String> out = new LinkedHashSet<>();
    boolean anyExpanded = false;
    for (String dep : deps) {
      List<String> cells = protoToCells.get(dep);
      if (cells != null) {
        out.addAll(cells);
        anyExpanded = true;
      } else {
        out.add(dep);
      }
    }
    return anyExpanded ? new ArrayList<>(out) : deps;
  }
}
