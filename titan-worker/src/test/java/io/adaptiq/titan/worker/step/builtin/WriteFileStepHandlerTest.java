package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link WriteFileStepHandler} — the shared TCK plus {@code writeFile}-specifics (Chunk 32E). */
class WriteFileStepHandlerTest extends StepHandlerTck {

  @Override
  protected StepHandler newHandler() {
    return new WriteFileStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("file", "out.txt", "text", "hello");
  }

  @Test
  void writesContentCreatingParentDirectories() throws Exception {
    StepResult result =
        new WriteFileStepHandler()
            .execute(request(Map.of("file", "build/reports/x.txt", "text", "content-here")));

    assertTrue(result.isSuccess());
    assertEquals("content-here", Files.readString(workDir.resolve("build/reports/x.txt")));
  }

  @Test
  void aMissingFileArgumentFails() throws Exception {
    StepResult result = new WriteFileStepHandler().execute(request(Map.of("text", "x")));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("file"), result.message());
  }
}
