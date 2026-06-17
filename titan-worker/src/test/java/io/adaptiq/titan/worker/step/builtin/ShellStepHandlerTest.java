package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ShellStepHandler} — runs the shared TCK plus {@code sh}-specific assertions (Chunk 32A).
 *
 * <p>Every test here runs a real {@code sh}; on a host without a POSIX shell on {@code PATH} the
 * whole class self-skips (an {@code sh} agent is a Linux/Unix host by definition).
 */
class ShellStepHandlerTest extends StepHandlerTck {

  @BeforeEach
  void requirePosixShell() {
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping the sh-handler tests");
  }

  private static boolean shAvailable() {
    try {
      return new ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Override
  protected StepHandler newHandler() {
    return new ShellStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("script", "echo tck-ok");
  }

  private StepRequest requestWith(Map<String, Object> arguments, CapturingLog log) {
    return new StepRequest(
        "sh",
        arguments,
        workDir,
        Map.of(),
        1L,
        "node-1",
        null,
        new LocalProcessExecutor(),
        log,
        new CapturingOutputs());
  }

  @Test
  void runsTheScriptAndStreamsItsOutput() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult result =
        new ShellStepHandler().execute(requestWith(Map.of("script", "echo titan-marker"), log));

    assertTrue(result.isSuccess());
    assertEquals(0, result.exitCode());
    assertTrue(
        log.text().contains("titan-marker"),
        "the script's stdout must reach the log: " + log.text());
  }

  @Test
  void acceptsTheYamlScalarShorthandUnderValue() throws Exception {
    // `- sh: make` parses to arguments {value: make}; the handler accepts either key.
    CapturingLog log = new CapturingLog();
    StepResult result =
        new ShellStepHandler().execute(requestWith(Map.of("value", "echo via-value"), log));

    assertTrue(result.isSuccess());
    assertTrue(log.text().contains("via-value"), log.text());
  }

  @Test
  void descriptorDeclaresTheScalarShorthandKey() {
    // design/42 §4.6 regression: the descriptor must declare value -> script so the worker's
    // normalization (and StepArgumentValidator) honour the `sh: echo hi` shorthand payload.
    assertEquals("script", new ShellStepHandler().descriptor().scalarShorthandKey());
  }

  @Test
  void aMissingScriptFailsTheStep() throws Exception {
    StepResult result = new ShellStepHandler().execute(request(Map.of()));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("script"), result.message());
  }

  @Test
  void aNonZeroExitFailsTheStepWithTheCode() throws Exception {
    StepResult result = new ShellStepHandler().execute(request(Map.of("script", "exit 3")));
    assertFalse(result.isSuccess());
    assertEquals(3, result.exitCode());
  }
}
