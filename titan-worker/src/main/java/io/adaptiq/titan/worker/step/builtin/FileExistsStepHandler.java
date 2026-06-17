package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The built-in {@code fileExists} step — publishes whether a workspace path exists (Chunk 32E).
 *
 * <p>Like {@code readFile}, the result is <em>published</em> as a step output (a boolean) rather
 * than returned: a downstream precondition can gate on {@code ${{ steps['…'].outputs.exists }} ==
 * true}. The step itself always succeeds — "the file is absent" is an answer, not a failure.
 */
public final class FileExistsStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "fileExists";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "fileExists",
        "File exists",
        "Publishes a boolean output for whether a workspace path exists.",
        List.of(
            ParamSpec.required("file", "string", "Workspace-relative path to test."),
            ParamSpec.optional(
                "output", "string", "Output key for the boolean result (default 'exists').")),
        // design/42 §4.6: the scalar shorthand `fileExists: <path>` resolves to `file`.
        "file");
  }

  @Override
  public StepResult execute(StepRequest request) {
    String file = request.argString("file");
    if (file == null || file.isBlank()) {
      return StepResult.failed("fileExists step has no 'file'");
    }
    Path target = request.workDir().resolve(file).normalize();
    boolean exists = Files.exists(target);
    request.outputs().put(request.argString("output", "exists"), exists);
    request.log().system("fileExists " + file + " -> " + exists);
    return StepResult.success();
  }
}
