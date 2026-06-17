package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.util.List;

/**
 * The built-in {@code echo} step — prints a message to the build console.
 *
 * <p>Writes to the {@code stdout} stream so the line is user-visible build output (and runs through
 * the worker's masking sink), not a {@code system} diagnostic. A multi-line message is split on
 * {@code \n} so each line is one {@code LogSink} line — the sink contract forbids embedded
 * newlines. Idempotent.
 */
public final class EchoStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "echo";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "echo",
        "Echo a message",
        "Prints a message to the build console.",
        List.of(
            ParamSpec.required("message", "string", "The message to print to the build console.")),
        // scalar shorthand `echo: <message>` resolves to `message`.
        "message");
  }

  @Override
  public StepResult execute(StepRequest request) {
    String message = request.argString("message", "");
    // split(-1) keeps a trailing empty line; an empty message yields exactly one empty line.
    // A \r is preserved verbatim — YAML scalar values are LF-normalised, so a CRLF can only
    // arrive via a runtime-substituted argument, and stripping it would be a silent edit.
    for (String line : message.split("\n", -1)) {
      request.log().line("stdout", line);
    }
    return StepResult.success();
  }
}
