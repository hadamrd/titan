package io.adaptiq.titan.e2e.step;

import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerContext;
import io.adaptiq.titan.worker.step.StepHandlerProvider;
import java.util.List;

/**
 * The {@link StepHandlerProvider} for the Titan E2E SPI-step fixture (design/42 §4.2).
 *
 * <p>Registered the JDK way — a {@code META-INF/services/io.adaptiq.titan.worker.step
 * .StepHandlerProvider} resource line — so {@code StepHandlerDiscovery} finds it via the exact same
 * {@code ServiceLoader} path the socle's own provider uses. It contributes the single
 * {@link GreetingStepHandler}.
 *
 * <p>{@link #apiVersion()} is left to the default ({@code StepApi.VERSION}) — the fixture is built
 * against the same step-SPI generation the worker runs, so it passes the version gate (§4.4).
 */
public final class GreetingStepHandlerProvider implements StepHandlerProvider {

    @Override
    public List<StepHandler> handlers(StepHandlerContext context) {
        return List.of(new GreetingStepHandler());
    }

    @Override
    public String describe() {
        return "Titan E2E SPI-step fixture";
    }
}
