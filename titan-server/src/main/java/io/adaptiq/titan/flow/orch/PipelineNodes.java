package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.PipelineNode;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure model-traversal helpers extracted from {@code TitanOrchestrator} (#357 decomposition). All
 * methods are static — no DAO, no build context, no state — so they are trivially testable.
 */
public final class PipelineNodes {

  private PipelineNodes() {}

  /** The {@link StepModel} for a node id, or {@code null} if the id is not an executable step. */
  @Nullable
  public static StepModel findStep(@NonNull PipelineModel model, @NonNull String nodeId) {
    for (StageModel stage : model.getStages()) {
      for (StepModel step : stage.getSteps()) {
        if (nodeId.equals(step.getId())) {
          return step;
        }
      }
    }
    return null;
  }

  /** The {@link StageModel} owning a step node id, or {@code null}. */
  @Nullable
  public static StageModel findStage(@NonNull PipelineModel model, @NonNull String nodeId) {
    for (StageModel stage : model.getStages()) {
      for (StepModel step : stage.getSteps()) {
        if (nodeId.equals(step.getId())) {
          return stage;
        }
      }
    }
    return null;
  }

  @NonNull
  public static Map<String, FlowNodeRow> byId(@NonNull List<FlowNodeRow> rows) {
    Map<String, FlowNodeRow> map = new HashMap<>();
    for (FlowNodeRow r : rows) {
      map.put(r.nodeId, r);
    }
    return map;
  }

  @NonNull
  public static Map<String, String> nameToId(@NonNull PipelineModel model) {
    Map<String, String> map = new HashMap<>();
    for (PipelineNode n : model.getAllNodes()) {
      map.put(n.getName(), n.getId());
    }
    return map;
  }
}
