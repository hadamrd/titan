package io.adaptiq.titan.worker.step;

import java.util.List;

/**
 * Test fixture — a {@link StepHandlerProvider} that throws on {@code handlers()}. {@link
 * StepRegistry} must absorb the exception and continue (design/42 §4.3 rule 4 — a broken plugin
 * must not crash the controller).
 */
public final class BrokenProvider implements StepHandlerProvider {

  @Override
  public List<StepHandler> handlers(StepHandlerContext context) {
    throw new RuntimeException("BrokenProvider.handlers() intentionally throws — registry test");
  }

  @Override
  public String describe() {
    return "Titan step-api broken-provider fixture";
  }
}
