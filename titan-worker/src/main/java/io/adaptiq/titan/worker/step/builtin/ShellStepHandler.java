package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.util.List;

/**
 * The built-in {@code sh} step — runs a shell script (Chunk 32A).
 *
 * <p>This is Titan dogfooding its own SPI: {@code sh} is not special-cased in the worker, it is a
 * {@link StepHandler} registered like any other. If the framework's own primitive did not go
 * through the public extension point, the extension point would be fiction (design/32 §9).
 *
 * <p>The handler builds the command and hands it to the request's {@link
 * io.adaptiq.titan.worker.step.StepExecutor} — so a {@code sh} step transparently runs locally or
 * inside a container depending on the step's {@code image}, with no code here aware of which.
 */
public final class ShellStepHandler implements StepHandler {

  /**
   * Reserved argument key carrying the user's <em>original</em> shell script when the step's real
   * {@code script} has been rewritten by an {@link io.adaptiq.titan.worker.step.ExecutionAugmenter}
   * — notably the {@code sshAgent:} wrapper (design/41 §4). When present, this is echoed as the
   * {@code $ …} command line in place of the wrapper's ssh-agent lifecycle preamble, so the user's
   * step console shows only the command they wrote. Absent for a normal {@code sh:} step, whose own
   * (possibly multi-line) script is echoed verbatim. The {@code titan.} prefix marks it
   * engine-internal so {@code StepArgumentValidator} never warns about it.
   */
  public static final String DISPLAY_SCRIPT_KEY = "titan.displayScript";

  @Override
  public String descriptorId() {
    return "sh";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "sh",
        "Shell script",
        "Runs a shell script on the agent, streaming its output to the build log. "
            + "A non-zero exit code fails the step.",
        List.of(ParamSpec.required("script", "string", "The shell script to run.")),
        // design/42 §4.6: the scalar shorthand `sh: echo hi` parses to {value: …};
        // declare that the worker resolves that generic `value` to `script` for this step.
        "script");
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    // The YAML scalar shorthand (`sh: make`) stores the script under "value"; the declarative
    // front-end uses "script". Accept either.
    String script = request.argString("script");
    if (script == null) {
      script = request.argString("value");
    }
    if (script == null || script.isBlank()) {
      return StepResult.failed("sh step has no 'script'");
    }
    // If an augmenter rewrote the script (the sshAgent: wrapper — design/41 §4), echo the
    // user's ORIGINAL command, not the injected ssh-agent preamble. null for a plain sh: step,
    // so the executor echoes the verbatim `sh -c <script>` the user actually wrote.
    String displayScript = request.argString(DISPLAY_SCRIPT_KEY);
    String displayCommand = displayScript == null ? null : "sh -c " + displayScript;
    int exit =
        request
            .executor()
            .run(
                List.of("sh", "-c", script),
                request.workDir(),
                request.env(),
                request.log(),
                displayCommand);
    return StepResult.ofExitCode(exit);
  }
}
