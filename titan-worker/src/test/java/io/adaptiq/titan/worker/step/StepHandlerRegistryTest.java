package io.adaptiq.titan.worker.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.builtin.ScriptStepHandler;
import io.adaptiq.titan.worker.step.builtin.ShellStepHandler;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link StepHandlerRegistry} — Chunk 32A. */
class StepHandlerRegistryTest {

  @Test
  void registersAndLooksUpBuiltInHandlers() {
    StepHandlerRegistry registry =
        new StepHandlerRegistry(List.of(new ShellStepHandler(), new ScriptStepHandler()));

    assertTrue(registry.find("sh").isPresent());
    assertTrue(registry.find("script").isPresent());
    assertTrue(registry.find("nonesuch").isEmpty(), "an unknown step type resolves to empty");
    assertEquals(java.util.Set.of("sh", "script"), registry.descriptorIds());
  }

  @Test
  void aDuplicateDescriptorIdIsRejected() {
    // Two handlers claiming "sh" is a misconfiguration the worker must refuse — never
    // a silent last-wins (design/32 §3.3).
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new StepHandlerRegistry(List.of(new ShellStepHandler(), new ShellStepHandler())));
    assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
  }

  @Test
  void aHandlerDisagreeingWithItsDescriptorIsRejected() {
    StepHandler liar =
        new StepHandler() {
          @Override
          public String descriptorId() {
            return "claimed";
          }

          @Override
          public StepDescriptor descriptor() {
            return new StepDescriptor("actual", "Liar", "help", List.of());
          }

          @Override
          public StepResult execute(StepRequest request) {
            return StepResult.success();
          }
        };
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> new StepHandlerRegistry(List.of(liar)));
    assertTrue(e.getMessage().contains("disagrees"), e.getMessage());
  }
}
