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
 * The built-in {@code readFile} step — reads a workspace file and publishes its content as a step
 * output (Chunk 32E).
 *
 * <p>A classic {@code readFile} <em>returns</em> the content to the pipeline script. Titan's DAG is
 * static — a step has no return channel — so {@code readFile} <em>publishes</em> the content
 * through the output store ({@code setOutput}); a downstream step reads it with {@code ${{
 * steps['…'].outputs.content }}}. The output key defaults to {@code content}.
 */
public final class ReadFileStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "readFile";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "readFile",
        "Read file",
        "Reads a workspace file and publishes its content as a step output.",
        List.of(
            ParamSpec.required("file", "string", "Workspace-relative path to read."),
            ParamSpec.optional(
                "output",
                "string",
                "Output key to publish the content under (default 'content').")),
        // design/42 §4.6: the scalar shorthand `readFile: <path>` resolves to `file`.
        "file");
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    String file = request.argString("file");
    if (file == null || file.isBlank()) {
      return StepResult.failed("readFile step has no 'file'");
    }
    Path target = request.workDir().resolve(file).normalize();
    if (!Files.isRegularFile(target)) {
      return StepResult.failed("readFile: no such file '" + file + "'");
    }
    String content = Files.readString(target, StandardCharsets.UTF_8);
    request.outputs().put(request.argString("output", "content"), content);
    request.log().system("read " + file + " (" + content.length() + " chars)");
    return StepResult.success();
  }
}
