package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.StageModel;

/**
 * The small carrier a {@link StepScope} is handed while {@link TitanYamlParser} walks a pipeline
 * (design/42 §4.7). It holds the location facts a scope needs to (a) build a located {@link
 * PipelineParseException} and (b) reach the {@link StageModel} currently being assembled — because
 * a stage-only scope ({@code when}, {@code dependsOn}) sets stage-level data, not step data, and
 * the {@code StepScope} method signatures deal in {@code StepModel}/{@code JsonNode}.
 *
 * <p>This is pure parser machinery — it deals only in the generic model, never in step descriptors
 * — so it is controller- and worker-safe and lives in {@code titan-pipeline-model}, not {@code
 * titan-step-api}.
 */
public final class ParseContext {

  @NonNull private final String location;

  @Nullable private final StageModel stage;

  private ParseContext(@NonNull String location, @Nullable StageModel stage) {
    this.location = location;
    this.stage = stage;
  }

  /** A context for a stage node — {@code stage} is the model being assembled for it. */
  @NonNull
  static ParseContext forStage(@NonNull String location, @NonNull StageModel stage) {
    return new ParseContext(location, stage);
  }

  /** A context for a single step node — no {@code StageModel} is being mutated. */
  @NonNull
  static ParseContext forStep(@NonNull String location) {
    return new ParseContext(location, null);
  }

  /** The located prefix for an error message — e.g. {@code "stage 'Deploy' step 0"}. */
  @NonNull
  public String location() {
    return location;
  }

  /** The {@link StageModel} being assembled, when this context is for a stage node. */
  @NonNull
  public StageModel stage() {
    if (stage == null) {
      throw new IllegalStateException("ParseContext.stage() is only available in a stage context");
    }
    return stage;
  }

  /** Build a located {@link PipelineParseException}. */
  @NonNull
  public PipelineParseException error(@NonNull String message) {
    return new PipelineParseException(location + ": " + message);
  }
}
