package io.adaptiq.titan.worker.step;

import java.util.List;

/** Test fixture — a minimal {@link StepHandler} used by {@link StepRegistryTest}. */
public final class FixtureStepHandler implements StepHandler {

  public static final String ID = "stepRegistryFixture";

  @Override
  public String descriptorId() {
    return ID;
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        ID,
        "Fixture step",
        "A minimal handler for StepRegistry tests.",
        List.of(ParamSpec.optional("value", "string", "Echoed back.")),
        "value");
  }

  @Override
  public StepResult execute(StepRequest request) {
    return StepResult.success();
  }
}
