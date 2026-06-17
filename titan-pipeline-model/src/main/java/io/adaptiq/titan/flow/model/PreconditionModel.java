package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * An automated runtime gate node in the pipeline DAG (design/29 §4, §7.1).
 *
 * <p>A precondition carries a non-Turing-complete CEL-subset {@link #expression} that is evaluated
 * at <em>run</em> time against published step outputs (design/29 §6). The node passes when the
 * expression is true and blocks downstream nodes otherwise. The evaluator is built in Chunk 6B; 6A
 * only needs the node in the model and in DAG validation — the expression string is opaque here.
 *
 * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
 */
public class PreconditionModel implements PipelineNode {

  @NonNull private String id = "";

  @NonNull private String name = "";

  /** The CEL-subset boolean expression evaluated at run time (design/29 §4). */
  @NonNull private String expression = "";

  /** Names of the nodes this precondition depends on. */
  @NonNull private List<String> dependsOn = new ArrayList<>();

  /** Default constructor for Jackson deserialization. */
  public PreconditionModel() {}

  @Override
  @NonNull
  public String getId() {
    return id;
  }

  public void setId(@NonNull String id) {
    this.id = id;
  }

  @Override
  @NonNull
  public String getName() {
    return name;
  }

  public void setName(@NonNull String name) {
    this.name = name;
  }

  @NonNull
  public String getExpression() {
    return expression;
  }

  public void setExpression(@NonNull String expression) {
    this.expression = expression;
  }

  @Override
  @NonNull
  public List<String> getDependsOn() {
    return dependsOn;
  }

  public void setDependsOn(@NonNull List<String> dependsOn) {
    this.dependsOn = dependsOn;
  }

  @Override
  @com.fasterxml.jackson.annotation.JsonIgnore
  @NonNull
  public String getNodeType() {
    return "PRECONDITION";
  }
}
