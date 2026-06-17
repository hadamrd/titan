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
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link SetOutputStepHandler} — the shared TCK plus {@code setOutput} single/multi forms. */
class SetOutputStepHandlerTest extends StepHandlerTck {

  @Override
  protected StepHandler newHandler() {
    return new SetOutputStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("name", "deployEnv", "value", "production");
  }

  private Result run(Map<String, Object> args) throws Exception {
    StepHandlerTck.CapturingOutputs outputs = new StepHandlerTck.CapturingOutputs();
    StepRequest req =
        new StepRequest(
            "setOutput",
            args,
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            new StepHandlerTck.CapturingLog(),
            outputs);
    return new Result(new SetOutputStepHandler().execute(req), outputs);
  }

  private record Result(StepResult result, StepHandlerTck.CapturingOutputs outputs) {}

  @Test
  void singleFormPublishesTheNamedOutput() throws Exception {
    Result r = run(Map.of("name", "deployEnv", "value", "production"));
    assertTrue(r.result().isSuccess());
    assertEquals("production", r.outputs().map.get("deployEnv"));
  }

  @Test
  void valuesFormPublishesEveryEntry() throws Exception {
    Result r =
        run(
            Map.of(
                "values",
                Map.of("deployEnv", "production", "version", "1.2.3", "region", "eu-west-1")));
    assertTrue(r.result().isSuccess());
    assertEquals("production", r.outputs().map.get("deployEnv"));
    assertEquals("1.2.3", r.outputs().map.get("version"));
    assertEquals("eu-west-1", r.outputs().map.get("region"));
  }

  @Test
  void aValueKeepsItsType() throws Exception {
    Result r = run(Map.of("name", "approved", "value", Boolean.TRUE));
    assertTrue(r.result().isSuccess());
    assertEquals(
        Boolean.TRUE,
        r.outputs().map.get("approved"),
        "a boolean output must not be flattened to a string");
  }

  @Test
  void missingBothFormsFails() throws Exception {
    Result r = run(Map.of());
    assertFalse(r.result().isSuccess());
    assertTrue(r.result().message().contains("'values'"), r.result().message());
  }

  @Test
  void nameWithoutValueFails() throws Exception {
    Result r = run(Map.of("name", "x"));
    assertFalse(r.result().isSuccess());
    assertTrue(r.result().message().contains("'value'"), r.result().message());
  }

  @Test
  void anEmptyValuesMapFails() throws Exception {
    Result r = run(Map.of("values", Map.of()));
    assertFalse(r.result().isSuccess());
  }

  @Test
  void valuesThatIsNotAMapFails() throws Exception {
    Result r = run(Map.of("values", "not-a-map"));
    assertFalse(r.result().isSuccess());
    assertTrue(r.result().message().contains("map"), r.result().message());
  }
}
