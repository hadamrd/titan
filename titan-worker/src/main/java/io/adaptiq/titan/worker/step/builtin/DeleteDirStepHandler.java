package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The built-in {@code deleteDir} step — recursively deletes a workspace directory (Chunk 32E).
 *
 * <p>Idempotent by construction (design/30 caveat 3): deleting an already-absent directory is a
 * success, so a reaped-and-retried {@code deleteDir} is always safe.
 */
public final class DeleteDirStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "deleteDir";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "deleteDir",
        "Delete directory",
        "Recursively deletes a workspace directory (defaults to the whole workspace).",
        List.of(
            ParamSpec.optional(
                "dir", "string", "Workspace-relative directory to delete (default '.').")),
        // design/42 §4.6: the scalar shorthand `deleteDir: <path>` resolves to `dir`.
        "dir");
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    Path target = request.workDir().resolve(request.argString("dir", ".")).normalize();
    if (!Files.exists(target)) {
      request.log().system("deleteDir: '" + target + "' already absent");
      return StepResult.success();
    }
    deleteRecursively(target, request.workDir());
    request.log().system("deleteDir: removed '" + target + "'");
    return StepResult.success();
  }

  /** Delete {@code target} and its contents; the workspace root itself is emptied, not removed. */
  private static void deleteRecursively(Path target, Path workDir) throws IOException {
    try (Stream<Path> walk = Files.walk(target)) {
      walk.sorted(Comparator.reverseOrder())
          .filter(p -> !p.equals(workDir)) // keep the workspace dir itself
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
    }
  }
}
