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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link ReadFileStepHandler} — the shared TCK plus {@code readFile}-specifics (Chunk 32E). */
class ReadFileStepHandlerTest extends StepHandlerTck {

  @BeforeEach
  void seedTheValidArgumentsFile() throws Exception {
    Files.writeString(workDir.resolve("in.txt"), "seeded");
  }

  @Override
  protected StepHandler newHandler() {
    return new ReadFileStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("file", "in.txt");
  }

  private StepRequest readRequest(Map<String, Object> arguments, CapturingOutputs outputs) {
    return new StepRequest(
        "readFile",
        arguments,
        workDir,
        Map.of(),
        1L,
        "n",
        null,
        new LocalProcessExecutor(),
        new CapturingLog(),
        outputs);
  }

  @Test
  void publishesFileContentAsTheContentOutput() throws Exception {
    Files.writeString(workDir.resolve("data.txt"), "the-payload");
    CapturingOutputs outputs = new CapturingOutputs();

    StepResult result =
        new ReadFileStepHandler().execute(readRequest(Map.of("file", "data.txt"), outputs));

    assertTrue(result.isSuccess());
    assertEquals("the-payload", outputs.map.get("content"));
  }

  @Test
  void honoursACustomOutputKey() throws Exception {
    Files.writeString(workDir.resolve("v.txt"), "9.9.9");
    CapturingOutputs outputs = new CapturingOutputs();

    new ReadFileStepHandler()
        .execute(readRequest(Map.of("file", "v.txt", "output", "version"), outputs));

    assertEquals("9.9.9", outputs.map.get("version"));
  }

  @Test
  void aMissingFileFailsTheStep() throws Exception {
    StepResult result = new ReadFileStepHandler().execute(request(Map.of("file", "ghost.txt")));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("no such file"), result.message());
  }
}
