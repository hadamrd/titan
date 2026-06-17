package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link FileExistsStepHandler} — the shared TCK plus {@code fileExists}-specifics (Chunk 32E). */
class FileExistsStepHandlerTest extends StepHandlerTck {

  @Override
  protected StepHandler newHandler() {
    return new FileExistsStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    // fileExists always succeeds — an absent file is an answer (false), not a failure.
    return Map.of("file", "anything");
  }

  private StepRequest existsRequest(String file, CapturingOutputs outputs) {
    return new StepRequest(
        "fileExists",
        Map.of("file", file),
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
  void publishesTrueForAPresentFile() throws Exception {
    Files.writeString(workDir.resolve("here.txt"), "x");
    CapturingOutputs outputs = new CapturingOutputs();

    new FileExistsStepHandler().execute(existsRequest("here.txt", outputs));

    assertEquals(true, outputs.map.get("exists"));
  }

  @Test
  void publishesFalseForAnAbsentFile() throws Exception {
    CapturingOutputs outputs = new CapturingOutputs();

    var result = new FileExistsStepHandler().execute(existsRequest("nope.txt", outputs));

    assertTrue(result.isSuccess(), "an absent file is not a failure");
    assertEquals(false, outputs.map.get("exists"));
  }
}
