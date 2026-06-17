package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.util.List;

/**
 * The built-in {@code error} step — deliberately fails the current node with a message.
 *
 * <p>The primitive for aborting a pipeline on a failed precondition (a missing version tag, an
 * unexpected environment). It returns a {@code FAILED} {@link StepResult}; it never throws —
 * node-failure semantics are the executor's job. Idempotent: a reaped re-run fails identically.
 */
public final class ErrorStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "error";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "error",
        "Fail the build",
        "Deliberately fails the current node with a message — the primitive for "
            + "aborting a pipeline on a failed precondition.",
        List.of(
            ParamSpec.required(
                "message", "string", "The failure message recorded against the node.")),
        // scalar shorthand `error: <message>` resolves to `message`.
        "message");
  }

  @Override
  public StepResult execute(StepRequest request) {
    String message = request.argString("message");
    if (message == null || message.isBlank()) {
      return StepResult.failed("error step called with no message");
    }
    request.log().line("stderr", message);
    return StepResult.failed(message);
  }
}
