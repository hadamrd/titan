package io.adaptiq.titan.e2e.step;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.util.List;

/**
 * A minimal Tier-2 {@link StepHandler} fixture for the Titan E2E harness (design/42 §4.2).
 *
 * <p>It contributes one step type — {@code e2eSpiGreeting} — that writes a greeting to the step
 * log and returns {@code SUCCESS}. It spawns no process and touches no worker internals: it is the
 * smallest possible handler that still proves Tier-2 ServiceLoader discovery works end to end.
 *
 * <p>The {@code who} argument folds in via the parser's generic scalar shorthand: a bare
 * {@code e2eSpiGreeting: "Titan E2E"} lands in the {@code value} key, which this handler's
 * {@link StepDescriptor#scalarShorthandKey()} resolves to {@code who} (design/42 §4.6).
 */
public final class GreetingStepHandler implements StepHandler {

    /** The step type this handler serves — referenced by id from the scenario pipeline. */
    public static final String ID = "e2eSpiGreeting";

    @Override
    public String descriptorId() {
        return ID;
    }

    @Override
    public StepDescriptor descriptor() {
        return new StepDescriptor(
                ID,
                "E2E SPI greeting",
                "Logs a greeting — the Titan E2E Tier-2 step-discovery fixture.",
                List.of(ParamSpec.optional("who", "string", "Who to greet.")),
                "who");
    }

    @Override
    public StepResult execute(StepRequest request) {
        String who = request.argString("who", request.argString("value", "world"));
        request.log().line("stdout", "Hello, " + who + " — from the Titan E2E SPI step.");
        return StepResult.success();
    }
}
