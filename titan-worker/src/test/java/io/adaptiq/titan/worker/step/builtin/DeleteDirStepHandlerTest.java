package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link DeleteDirStepHandler} — the shared TCK plus {@code deleteDir}-specifics (Chunk 32E). */
class DeleteDirStepHandlerTest extends StepHandlerTck {

  @Override
  protected StepHandler newHandler() {
    return new DeleteDirStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of(); // delete '.' — an empty workspace, succeeds
  }

  @Test
  void deletesADirectoryTree() throws Exception {
    Files.createDirectories(workDir.resolve("build/classes"));
    Files.writeString(workDir.resolve("build/classes/A.class"), "bytecode");

    assertTrue(new DeleteDirStepHandler().execute(request(Map.of("dir", "build"))).isSuccess());
    assertFalse(Files.exists(workDir.resolve("build")), "the directory tree must be gone");
  }

  @Test
  void deletingAnAbsentDirectoryIsSuccess() throws Exception {
    // Idempotent — a reaped-and-retried deleteDir must be safe (design/30 caveat 3).
    assertTrue(
        new DeleteDirStepHandler().execute(request(Map.of("dir", "never-existed"))).isSuccess());
  }

  @Test
  void emptyingTheWorkspaceKeepsTheWorkspaceDirectoryItself() throws Exception {
    Files.writeString(workDir.resolve("stale.txt"), "x");

    assertTrue(new DeleteDirStepHandler().execute(request(Map.of())).isSuccess());
    assertTrue(Files.isDirectory(workDir), "the workspace root itself must survive");
    assertFalse(Files.exists(workDir.resolve("stale.txt")), "its contents must be gone");
  }
}
