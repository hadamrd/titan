package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ScriptStepHandler} — TCK plus assertions on the four builtins ({@code echo}/{@code
 * setOutput}/{@code error}/{@code sh}). Library reuse goes through {@code libraryCall} (design/53),
 * not script bodies.
 */
class ScriptStepHandlerTest extends StepHandlerTck {

  @Override
  protected StepHandler newHandler() {
    return new ScriptStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("runtime", "groovy", "body", "echo 'tck ok'");
  }

  private StepRequest requestWith(
      Map<String, Object> arguments, CapturingLog log, CapturingOutputs outputs) {
    return new StepRequest(
        "script",
        arguments,
        workDir,
        Map.of(),
        1L,
        "node-1",
        null,
        new LocalProcessExecutor(),
        log,
        outputs);
  }

  @Test
  void echoAndSetOutputBuiltinsWork() throws Exception {
    CapturingLog log = new CapturingLog();
    CapturingOutputs outputs = new CapturingOutputs();
    String body = "echo 'hello from groovy'\nsetOutput('version', '1.4.2')\n";

    StepResult result =
        new ScriptStepHandler().execute(requestWith(Map.of("body", body), log, outputs));

    assertTrue(result.isSuccess(), result.message());
    assertTrue(log.text().contains("hello from groovy"), log.text());
    assertEquals("1.4.2", outputs.map.get("version"), "setOutput must publish to the output sink");
  }

  @Test
  void aThrowingBodyFailsTheStep() throws Exception {
    StepResult result =
        new ScriptStepHandler().execute(request(Map.of("body", "error('deliberate boom')")));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("deliberate boom"), result.message());
  }

  @Test
  void aMissingBodyFailsTheStep() throws Exception {
    StepResult result = new ScriptStepHandler().execute(request(Map.of()));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("body"), result.message());
  }

  // ── adversarial: shared-library vars/ binding (issue #681) ───────────────

  /**
   * A staged shared-library {@code vars/<name>.groovy} must be callable as {@code <name>(args)}
   * from the body, and the four binding builtins ({@code echo}, {@code setOutput}, etc.) must be
   * visible inside the vars/ method itself — the same delegate-chain shape a classic shared-library
   * function relies on.
   */
  @Test
  void sharedLibraryVarsMethodIsCallableFromBody(@TempDir Path libRoot) throws Exception {
    Path vars = libRoot.resolve("moab-shared").resolve("vars");
    Files.createDirectories(vars);
    Files.writeString(
        vars.resolve("initBindings.groovy"),
        "def call(Map args = [:]) {\n"
            + "  echo \"[moab-shared] initBindings env=${args.env}\"\n"
            + "  return [component: 'moab-core', env: args.env]\n"
            + "}\n");

    CapturingLog log = new CapturingLog();
    CapturingOutputs outputs = new CapturingOutputs();
    String body =
        "def b = initBindings(env: 'ci')\n"
            + "echo \"component is ${b.component}\"\n"
            + "setOutput('component', b.component)\n";
    StepRequest request =
        requestWith(Map.of("body", body, "libraries", List.of("moab-shared")), log, outputs);

    StepResult result = new ScriptStepHandler(libRoot).execute(request);

    assertTrue(result.isSuccess(), "body must reach the vars/ method: " + result.message());
    assertTrue(log.text().contains("[moab-shared] initBindings env=ci"), log.text());
    assertTrue(log.text().contains("component is moab-core"), log.text());
    assertEquals("moab-core", outputs.map.get("component"));
  }

  /**
   * The script body must NOT silently no-op when it calls a non-existent vars/ method — that would
   * mask a typo in a pipeline. The step fails loudly, surfacing Groovy's {@code
   * MissingMethodException} message ("No signature of method") so the author can fix the call.
   */
  @Test
  void unknownVarsMethodFailsLoudlyNotSilently(@TempDir Path libRoot) throws Exception {
    Path vars = libRoot.resolve("moab-shared").resolve("vars");
    Files.createDirectories(vars);
    Files.writeString(vars.resolve("known.groovy"), "def call(Map args = [:]) { return 'ok' }\n");

    CapturingLog log = new CapturingLog();
    CapturingOutputs outputs = new CapturingOutputs();
    // The body references a method that has no vars/ file. The step must fail, not no-op.
    StepRequest request =
        requestWith(
            Map.of("body", "doesNotExist(env: 'ci')\n", "libraries", List.of("moab-shared")),
            log,
            outputs);

    StepResult result = new ScriptStepHandler(libRoot).execute(request);

    assertFalse(result.isSuccess(), "calling an unknown vars/ method must fail the step");
    assertTrue(
        result.message().contains("doesNotExist")
            || result.message().toLowerCase().contains("no signature of method"),
        "failure message must point at the bad call: " + result.message());
  }
}
