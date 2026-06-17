package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A top-level node in the pipeline DAG — a {@link StageModel stage}, a {@link GateModel gate}, or a
 * {@link PreconditionModel precondition}. DAG edges are expressed as {@link #getDependsOn()}
 * (dependency by node <em>name</em>, design/29 §3); a node with no dependencies is a root.
 *
 * <p>This common view lets the DAG validator (design/31 6A) and the materialiser (6C) treat the
 * three node kinds uniformly without instanceof ladders.
 */
public interface PipelineNode {

  /** Unique node id within the pipeline. */
  @NonNull
  String getId();

  /** Human-readable node name — the identity {@code dependsOn} entries reference. */
  @NonNull
  String getName();

  /** Names of the nodes this node depends on; empty for a root node. */
  @NonNull
  List<String> getDependsOn();

  /** Discriminator: {@code "STAGE"}, {@code "GATE"} or {@code "PRECONDITION"}. */
  @NonNull
  String getNodeType();
}
