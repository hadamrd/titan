package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Thrown when a Titan YAML pipeline definition cannot be turned into a valid {@link
 * io.adaptiq.titan.flow.model.PipelineModel} — a syntax error, an unknown key, a schema violation,
 * or a broken DAG (cycle, missing dependency, duplicate id, self-dependency).
 *
 * <p>Every message is located: it names the offending key/node and, where the YAML parser supplies
 * it, the line. A bake fails on this exception — design/26 Tier B: a malformed pipeline is rejected
 * at bake time, never thirty minutes into a run.
 */
public class PipelineParseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public PipelineParseException(String message) {
    super(message);
  }

  public PipelineParseException(String message, @Nullable Throwable cause) {
    super(message, cause);
  }
}
