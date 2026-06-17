package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PipelineNode;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates a parsed {@link PipelineModel} as a well-formed DAG before it is baked (design/29 §3,
 * design/26 Tier B). The checks, all of which fail the bake rather than a 30-minute-in run:
 *
 * <ul>
 *   <li>duplicate node names (the identity {@code dependsOn} references);
 *   <li>duplicate node ids and duplicate step ids;
 *   <li>a {@code dependsOn} pointing at a node that does not exist;
 *   <li>a node depending on itself;
 *   <li>a dependency cycle.
 * </ul>
 *
 * <p>Stateless — call {@link #validate(PipelineModel)} directly.
 */
public final class PipelineDagValidator {

  private PipelineDagValidator() {}

  /**
   * Validate {@code model}'s DAG. Returns normally if the DAG is sound. When called from outside
   * the parser (tests, direct constructors) {@code onFailure:} targets resolve only against
   * declared stage names — see {@link #validateDagAndOnFailure} for the parser path that also
   * accepts matrix/each prototype names (design/68 D3).
   *
   * @throws PipelineParseException with a located message on the first defect found.
   */
  public static void validate(@NonNull PipelineModel model) {
    validateDagAndOnFailure(model, Set.of());
  }

  /**
   * Parser-facing entry (design/68 #947): validate {@code model}'s DAG and {@code onFailure:}
   * targets, with an additional set of <em>known</em> stage names — the matrix/each prototype names
   * recorded by {@link PrototypeExpansion} during the parse. {@code onFailure:} references resolve
   * against the union of declared post-expansion stage names AND these prototype names: the PDL
   * contract is the user-written name, so a reference to a prototype that fanned out into cells
   * still resolves (design/68 D3).
   *
   * @throws PipelineParseException with a located message on the first defect found.
   */
  public static void validateDagAndOnFailure(
      @NonNull PipelineModel model, @NonNull Set<String> extraKnownStageNames) {
    List<PipelineNode> nodes = model.getAllNodes();

    // --- duplicate node names / ids ----------------------------------------------
    Map<String, String> nameToId = new HashMap<>();
    Set<String> ids = new HashSet<>();
    for (PipelineNode n : nodes) {
      if (n.getName().isEmpty()) {
        throw new PipelineParseException(
            n.getNodeType() + " node id='" + n.getId() + "' has an empty name");
      }
      if (nameToId.putIfAbsent(n.getName(), n.getId()) != null) {
        throw new PipelineParseException(
            "duplicate node name '"
                + n.getName()
                + "' — node names must be unique "
                + "(they are the identity dependsOn references)");
      }
      if (!ids.add(n.getId())) {
        throw new PipelineParseException("duplicate node id '" + n.getId() + "'");
      }
    }

    // --- duplicate step ids ------------------------------------------------------
    Set<String> stepIds = new HashSet<>();
    for (StageModel stage : model.getStages()) {
      for (StepModel step : stage.getSteps()) {
        if (!stepIds.add(step.getId())) {
          throw new PipelineParseException(
              "duplicate step id '" + step.getId() + "' in stage '" + stage.getName() + "'");
        }
      }
    }

    // --- dependsOn targets exist; no self-dependency -----------------------------
    Set<String> names = nameToId.keySet();
    for (PipelineNode n : nodes) {
      for (String dep : n.getDependsOn()) {
        if (dep.equals(n.getName())) {
          throw new PipelineParseException("node '" + n.getName() + "' depends on itself");
        }
        if (!names.contains(dep)) {
          throw new PipelineParseException(
              "node '"
                  + n.getName()
                  + "' dependsOn '"
                  + dep
                  + "', which is not a defined stage/gate/precondition");
        }
      }
    }

    // --- onFailure targets exist (design/68 #947) --------------------------------
    // onFailure references resolve against the union of declared stage names and the
    // matrix/each prototype names the caller threaded in (design/68 D3). Self-reference
    // and empty-list were already rejected at parse time inside OnFailureScope; here we
    // only catch unknown ids.
    validateOnFailureTargets(model, names, extraKnownStageNames);

    // --- cycle detection (DFS, three-colour) -------------------------------------
    Map<String, List<String>> graph = new HashMap<>();
    for (PipelineNode n : nodes) {
      graph.put(n.getName(), new ArrayList<>(n.getDependsOn()));
    }
    detectCycle(graph);
  }

  /**
   * Check that every {@code onFailure:} target on every stage resolves to a known stage name
   * (declared post-expansion stage names + caller-supplied matrix/each prototype names, per
   * design/68 D3). Package-private so the parser can also call it directly with prototype names
   * threaded in from {@link PrototypeExpansion}.
   */
  static void validateOnFailureTargets(
      @NonNull PipelineModel model,
      @NonNull Set<String> declaredNames,
      @NonNull Set<String> extraKnownStageNames) {
    if (model.getStages().isEmpty()) {
      return;
    }
    Set<String> known = new LinkedHashSet<>(declaredNames);
    known.addAll(extraKnownStageNames);
    for (StageModel stage : model.getStages()) {
      for (String target : stage.getOnFailureStages()) {
        if (!known.contains(target)) {
          throw new PipelineParseException(
              "stage '"
                  + stage.getName()
                  + "' onFailure references '"
                  + target
                  + "', which is not a defined stage (design/68)");
        }
      }
    }
  }

  /** Three-colour DFS over the dependency graph; throws on the first back-edge found. */
  private static void detectCycle(@NonNull Map<String, List<String>> graph) {
    Set<String> visited = new HashSet<>();
    Set<String> onStack = new HashSet<>();
    Deque<String> path = new ArrayDeque<>();
    for (String node : graph.keySet()) {
      if (!visited.contains(node)) {
        dfs(node, graph, visited, onStack, path);
      }
    }
  }

  private static void dfs(
      @NonNull String node,
      @NonNull Map<String, List<String>> graph,
      @NonNull Set<String> visited,
      @NonNull Set<String> onStack,
      @NonNull Deque<String> path) {
    visited.add(node);
    onStack.add(node);
    path.addLast(node);
    for (String dep : graph.getOrDefault(node, List.of())) {
      if (onStack.contains(dep)) {
        List<String> cycle = new ArrayList<>(path);
        cycle.add(dep);
        throw new PipelineParseException("dependency cycle: " + String.join(" -> ", cycle));
      }
      if (!visited.contains(dep)) {
        dfs(dep, graph, visited, onStack, path);
      }
    }
    onStack.remove(node);
    path.removeLast();
  }
}
