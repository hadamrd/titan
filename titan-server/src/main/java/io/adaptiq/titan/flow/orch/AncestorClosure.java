package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.GateModel;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PreconditionModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure DAG ancestry helper (issue #945). Computes, for every node in a {@link PipelineModel}, the
 * transitive set of nodes that must complete before it can run — i.e. its <em>ancestor closure</em>
 * across {@code dependsOn} (stages / gates / preconditions reference each other by name) and {@code
 * parentIds} (intra-stage step ordering).
 *
 * <h2>Why this exists — the issue #945 bug</h2>
 *
 * <p>Pre-fix, {@code TitanOrchestrator#finishIfDone} reacted to "any node FAILED" under {@code
 * blockOnFailure} by sweeping <strong>every</strong> non-terminal node to {@code SKIPPED}. That is
 * a DAG-contract violation: in a pipeline with two independent chains {@code Checkout -> A1 -> A2}
 * and {@code Checkout -> B1 -> B2}, a failure in {@code A1} would incorrectly skip {@code B1} /
 * {@code B2} even though their only common ancestor — {@code Checkout} — succeeded.
 *
 * <p>The fix needs to ask "<em>does this specific node's ancestor closure contain a failed
 * node?</em>" — and that is the single question this helper answers. It pre-computes the closure
 * once per advance pass so the sweep is O(N) lookups instead of O(N²) repeated DAG walks.
 *
 * <p>Semantics (matches GitHub Actions / GitLab CI / Buildkite):
 *
 * <ul>
 *   <li>Stage ancestors = stages/gates/preconditions reachable via {@code dependsOn} (by name).
 *   <li>Gate / precondition ancestors = same closure rule via their own {@code dependsOn}.
 *   <li>Step ancestors = (a) its owning stage + the stage's ancestor closure, plus (b) the steps in
 *       the same stage reachable via {@code parentIds} (and their step ancestors transitively).
 * </ul>
 *
 * <p>Each closure is keyed by the same id the orchestrator uses on {@code flow_nodes.node_id} —
 * stage id, gate id, precondition id, or step id — so the caller can index directly into the {@link
 * io.adaptiq.titan.store.rows.FlowNodeRow}-by-id map already on hand.
 *
 * <p>Pure / stateless: no DB reads, no time, no logging. Trivially testable; trivially cacheable
 * per pipeline-model version if a future optimisation wants that.
 */
public final class AncestorClosure {

  private final Map<String, Set<String>> closureByNodeId;

  private AncestorClosure(@NonNull Map<String, Set<String>> closure) {
    this.closureByNodeId = closure;
  }

  /**
   * Compute the ancestor closure for every node and step in the pipeline.
   *
   * @param model the pipeline (stages + gates + preconditions; never {@code null})
   * @return an indexable closure; lookup is by node id (stage / gate / precondition / step id)
   */
  @NonNull
  public static AncestorClosure of(@NonNull PipelineModel model) {
    Map<String, String> nameToId = PipelineNodes.nameToId(model);

    // First, the direct-parent map for every DAG node (stages/gates/preconditions). Steps are
    // appended after, because their direct parents are step ids inside the same stage AND the
    // owning stage id itself.
    Map<String, List<String>> directParents = new HashMap<>();
    for (StageModel st : model.getStages()) {
      directParents.put(st.getId(), resolveNames(st.getDependsOn(), nameToId));
    }
    for (GateModel g : model.getGates()) {
      directParents.put(g.getId(), resolveNames(g.getDependsOn(), nameToId));
    }
    for (PreconditionModel p : model.getPreconditions()) {
      directParents.put(p.getId(), resolveNames(p.getDependsOn(), nameToId));
    }
    for (StageModel st : model.getStages()) {
      for (StepModel step : st.getSteps()) {
        List<String> parents = new ArrayList<>();
        // The owning stage is a parent: a step cannot start until its stage is RUNNING, and a
        // skipped stage must skip its steps.
        parents.add(st.getId());
        // Sibling steps via parentIds (intra-stage chain).
        parents.addAll(step.getParentIds());
        directParents.put(step.getId(), parents);
      }
    }

    // Closure via memoised iterative DFS — handles diamonds cleanly and is robust against
    // any (already-rejected-at-parse-time) cycles.
    Map<String, Set<String>> closure = new HashMap<>();
    for (String nodeId : directParents.keySet()) {
      computeClosure(nodeId, directParents, closure);
    }
    return new AncestorClosure(closure);
  }

  /**
   * The ancestor ids of {@code nodeId} — all stages / gates / preconditions / steps that must
   * terminate before this node can advance. Returns an empty set if the node has no ancestors or is
   * unknown to the model (defensive — an unknown id should not crash the sweep).
   */
  @NonNull
  public Set<String> ancestorsOf(@NonNull String nodeId) {
    Set<String> a = closureByNodeId.get(nodeId);
    return a == null ? Collections.emptySet() : a;
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  @NonNull
  private static List<String> resolveNames(
      @NonNull List<String> dependsOnNames, @NonNull Map<String, String> nameToId) {
    List<String> ids = new ArrayList<>(dependsOnNames.size());
    for (String name : dependsOnNames) {
      String id = nameToId.get(name);
      if (id != null) {
        ids.add(id);
      }
    }
    return ids;
  }

  private static Set<String> computeClosure(
      @NonNull String start,
      @NonNull Map<String, List<String>> directParents,
      @NonNull Map<String, Set<String>> memo) {
    Set<String> cached = memo.get(start);
    if (cached != null) {
      return cached;
    }
    Set<String> visited = new LinkedHashSet<>();
    Set<String> onStack = new HashSet<>();
    Deque<String> stack = new ArrayDeque<>();
    stack.push(start);
    onStack.add(start);
    // Iterative DFS — push direct parents one by one, accumulate into `visited`.
    while (!stack.isEmpty()) {
      String cur = stack.peek();
      List<String> parents = directParents.getOrDefault(cur, Collections.emptyList());
      boolean descended = false;
      for (String p : parents) {
        if (onStack.contains(p)) {
          continue; // cycle guard — defensive only; parse-time validator forbids cycles
        }
        Set<String> pCached = memo.get(p);
        if (pCached != null) {
          if (!p.equals(start)) {
            visited.add(p);
          }
          visited.addAll(pCached);
          continue;
        }
        if (!visited.contains(p)) {
          stack.push(p);
          onStack.add(p);
          descended = true;
          break;
        }
      }
      if (!descended) {
        stack.pop();
        onStack.remove(cur);
        if (!cur.equals(start)) {
          visited.add(cur);
        }
      }
    }
    memo.put(start, visited);
    return visited;
  }
}
