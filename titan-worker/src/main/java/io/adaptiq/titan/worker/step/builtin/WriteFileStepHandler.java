package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The built-in {@code writeFile} step — writes text to a workspace file (Chunk 32E).
 *
 * <p>A non-process step: it manipulates the workspace, it does not spawn a process, so it ignores
 * the request's {@code StepExecutor}. One of the migration-frequency-prioritised built-ins
 * (design/32 §9 / §12 D5) — a pure-Java handler, ~no dependencies, fully on the SPI.
 */
public final class WriteFileStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "writeFile";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "writeFile",
        "Write file",
        "Writes text content to a file in the workspace, creating parent directories.",
        List.of(
            ParamSpec.required("file", "string", "Workspace-relative path to write."),
            ParamSpec.required("text", "string", "The content to write.")));
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    String file = request.argString("file");
    if (file == null || file.isBlank()) {
      return StepResult.failed("writeFile step has no 'file'");
    }
    String text = request.argString("text", "");
    Path target = request.workDir().resolve(file).normalize();
    if (target.getParent() != null) {
      Files.createDirectories(target.getParent());
    }
    Files.writeString(target, text, StandardCharsets.UTF_8);
    request.log().system("wrote " + file + " (" + text.length() + " chars)");
    return StepResult.success();
  }
}
