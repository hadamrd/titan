package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepArgumentValidator;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.builtin.GitStepHandler;
import io.adaptiq.titan.worker.step.builtin.ShellStepHandler;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The design/42 §4.5/§4.6 seam regression: a pipeline {@code sh} step whose {@code arguments} carry
 * only the generic scalar-shorthand {@code value} key (the payload shape the controller's
 * grammar-unaware parser emits for {@code sh: echo hi}) must, on the worker, be normalized to the
 * step's named argument so {@link StepArgumentValidator} accepts it and {@code execute()} sees the
 * named key.
 *
 * <p>Before the fix the validator demanded {@code script}, saw only {@code value}, and rejected the
 * step with {@code "argument validation failed: parameter 'script' ... is required but is missing"}
 * before {@code execute()} ran. These tests pin the normalization + descriptor declaration.
 */
final class TaskExecutorScalarShorthandTest {

  @Test
  void shShorthandValueIsNormalizedToScriptAndPassesValidation() {
    StepDescriptor descriptor = new ShellStepHandler().descriptor();
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("value", "echo hi"); // exactly what the controller emits for `sh: echo hi`

    TaskExecutor.applyScalarShorthand(arguments, descriptor);

    // execute() now sees the named `script` argument...
    assertEquals("echo hi", arguments.get("script"));
    // ...and so does the validator, which previously failed the step here.
    StepArgumentValidator.Result r = StepArgumentValidator.validate(descriptor, arguments);
    assertTrue(r.ok(), () -> "sh value-only shorthand must validate: " + r.failureMessage());
  }

  @Test
  void gitShorthandValueIsNormalizedToUrlAndPassesValidation() {
    StepDescriptor descriptor = new GitStepHandler().descriptor();
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("value", "https://example.com/repo.git");

    TaskExecutor.applyScalarShorthand(arguments, descriptor);

    assertEquals("https://example.com/repo.git", arguments.get("url"));
    StepArgumentValidator.Result r = StepArgumentValidator.validate(descriptor, arguments);
    assertTrue(r.ok(), () -> "git value-only shorthand must validate: " + r.failureMessage());
  }

  @Test
  void shValueOnlyWithoutNormalizationFailsValidation() {
    // The pre-fix behaviour, pinned: without the normalization the validator rejects the step.
    StepDescriptor descriptor = new ShellStepHandler().descriptor();
    StepArgumentValidator.Result r =
        StepArgumentValidator.validate(descriptor, Map.of("value", "echo hi"));
    assertFalse(r.ok());
    assertTrue(r.failureMessage().contains("script"), r.failureMessage());
  }

  @Test
  void anExplicitNamedArgumentIsNotOverwrittenByValue() {
    // If both `script` and `value` are present, the explicit named argument wins.
    StepDescriptor descriptor = new ShellStepHandler().descriptor();
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("script", "echo explicit");
    arguments.put("value", "echo shorthand");

    TaskExecutor.applyScalarShorthand(arguments, descriptor);

    assertEquals("echo explicit", arguments.get("script"));
  }

  @Test
  void aDescriptorWithNoShorthandKeyLeavesArgumentsUntouched() {
    // writeFile has two required params and no single-value scalar form — null shorthand key.
    StepDescriptor noShorthand =
        new StepDescriptor("writeFile", "Write file", "help", java.util.List.of());
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("value", "ignored");

    TaskExecutor.applyScalarShorthand(arguments, noShorthand);

    assertEquals(1, arguments.size());
    assertFalse(arguments.containsKey("script"));
  }

  @Test
  void noValueKeyIsANoOp() {
    StepDescriptor descriptor = new ShellStepHandler().descriptor();
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("script", "echo hi");

    TaskExecutor.applyScalarShorthand(arguments, descriptor);

    assertEquals(1, arguments.size());
    assertEquals("echo hi", arguments.get("script"));
  }
}
