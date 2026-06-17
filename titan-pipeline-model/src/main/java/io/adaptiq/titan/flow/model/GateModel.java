package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * A manual-approval gate node in the pipeline DAG (design/29 §3, §7.1).
 *
 * <p>A gate blocks every downstream node until an authorised approver acts. It carries no step
 * bodies — it is a pure DAG control node. Runtime approval handling is wired in Chunk 6F; 6A only
 * needs the gate to exist in the model and participate in DAG validation.
 *
 * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
 */
public class GateModel implements PipelineNode {

  @NonNull private String id = "";

  @NonNull private String name = "";

  /** Whether the gate requires an explicit human approval to pass. */
  private boolean requiresApproval = true;

  /** Identities (users / groups) permitted to approve this gate. */
  @NonNull private List<String> approvers = new ArrayList<>();

  /** Names of the nodes this gate depends on. */
  @NonNull private List<String> dependsOn = new ArrayList<>();

  /** Default constructor for Jackson deserialization. */
  public GateModel() {}

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

  public boolean isRequiresApproval() {
    return requiresApproval;
  }

  public void setRequiresApproval(boolean requiresApproval) {
    this.requiresApproval = requiresApproval;
  }

  @NonNull
  public List<String> getApprovers() {
    return approvers;
  }

  public void setApprovers(@NonNull List<String> approvers) {
    this.approvers = approvers;
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
    return "GATE";
  }
}
