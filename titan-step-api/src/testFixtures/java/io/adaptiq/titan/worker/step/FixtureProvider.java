package io.adaptiq.titan.worker.step;

import java.util.List;

/** Test fixture — a {@link StepHandlerProvider} that contributes {@link FixtureStepHandler}. */
public final class FixtureProvider implements StepHandlerProvider {

  @Override
  public List<StepHandler> handlers(StepHandlerContext context) {
    return List.of(new FixtureStepHandler());
  }

  @Override
  public String describe() {
    return "Titan step-api test fixture";
  }
}
