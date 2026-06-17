package io.adaptiq.titan.flow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A structured, typed {@code when:} guard on a step (GH #1093) — a <strong>discriminated
 * union</strong> of the three condition kinds Titan supports:
 *
 * <ul>
 *   <li>{@code when.branch: <glob>} — run only when the build's branch matches the glob;
 *   <li>{@code when.previous: success|failure|always} — run based on the prior step's outcome;
 *   <li>{@code when.files_changed: [<glob>...]} — run only when a changed file matches a glob.
 * </ul>
 *
 * <p>Exactly one discriminator is set; {@link #getKind()} names which. This is the typed
 * alternative to the legacy free-form CEL string {@code when:} ({@link StepModel#getWhen()}) — a
 * step carries <em>either</em> a string {@code when:} <em>or</em> a structured one, never both. The
 * grammar's {@code when:} key is a {@code oneOf[string, object]}; {@code WhenScope} picks the
 * branch at parse time.
 *
 * <p>Instances are Jackson-friendly (public no-arg constructor + getters/setters) so the baked
 * {@link PipelineModel} round-trips through {@code pipeline_model_json}. Construction is normally
 * via the {@link #ofBranch}/{@link #ofPrevious}/{@link #ofFilesChanged} factories, which set the
 * discriminator consistently.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WhenCondition {

  @Nullable private WhenKind kind;
  @Nullable private String branch;
  @Nullable private PreviousOutcome previous;
  @Nullable private List<String> filesChanged;

  /** Default constructor for Jackson deserialization. */
  public WhenCondition() {}

  /** A {@code when.branch: <glob>} condition. */
  @NonNull
  public static WhenCondition ofBranch(@NonNull String glob) {
    WhenCondition c = new WhenCondition();
    c.kind = WhenKind.BRANCH;
    c.branch = glob;
    return c;
  }

  /** A {@code when.previous: success|failure|always} condition. */
  @NonNull
  public static WhenCondition ofPrevious(@NonNull PreviousOutcome outcome) {
    WhenCondition c = new WhenCondition();
    c.kind = WhenKind.PREVIOUS;
    c.previous = outcome;
    return c;
  }

  /** A {@code when.files_changed: [<glob>...]} condition. */
  @NonNull
  public static WhenCondition ofFilesChanged(@NonNull List<String> globs) {
    WhenCondition c = new WhenCondition();
    c.kind = WhenKind.FILES_CHANGED;
    c.filesChanged = new ArrayList<>(globs);
    return c;
  }

  @Nullable
  public WhenKind getKind() {
    return kind;
  }

  public void setKind(@Nullable WhenKind kind) {
    this.kind = kind;
  }

  @Nullable
  public String getBranch() {
    return branch;
  }

  public void setBranch(@Nullable String branch) {
    this.branch = branch;
  }

  @Nullable
  public PreviousOutcome getPrevious() {
    return previous;
  }

  public void setPrevious(@Nullable PreviousOutcome previous) {
    this.previous = previous;
  }

  @Nullable
  public List<String> getFilesChanged() {
    return filesChanged;
  }

  public void setFilesChanged(@Nullable List<String> filesChanged) {
    this.filesChanged = filesChanged;
  }

  @Override
  public String toString() {
    return "WhenCondition{kind="
        + kind
        + ", branch="
        + branch
        + ", previous="
        + previous
        + ", filesChanged="
        + filesChanged
        + '}';
  }
}
