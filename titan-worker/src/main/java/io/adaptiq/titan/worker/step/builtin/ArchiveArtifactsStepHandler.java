package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.apache.tools.ant.DirectoryScanner;

/**
 * The built-in {@code archiveArtifacts} step — persists workspace files matching an Ant-style glob,
 * through the {@link io.adaptiq.titan.worker.step.ArtifactSink} (design/41 §8.2, 32E-3).
 *
 * <p>Titan's worker holds <em>both</em> the workspace files and a direct store connection, so this
 * handler globs the local workspace and hands each match to the sink. No channel, no controller
 * round-trip — the channel-less architecture paying out (design/41 §8.1).
 *
 * <p>Glob expansion is Ant's {@link DirectoryScanner}, so the {@code **}/{@code *}/{@code ?}
 * patterns and include/exclude interplay behave as expected.
 *
 * <p>Idempotent (design/32 §4): re-archiving overwrites by {@code (build, name)}, so a reaped,
 * re-run step converges. {@code onlyIfSuccessful} is intentionally absent — conditional execution
 * is the DAG's {@code when:} (design/41 E7).
 */
public final class ArchiveArtifactsStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "archiveArtifacts";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "archiveArtifacts",
        "Archive artifacts",
        "Archives workspace files matching an Ant-style glob, so they persist with the "
            + "build and can be browsed and downloaded after it finishes.",
        List.of(
            ParamSpec.required(
                "artifacts",
                "string",
                "Ant-style include glob(s) — comma- or space-separated, "
                    + "e.g. 'target/*.jar, build/reports/**'."),
            ParamSpec.optional(
                "excludes", "string", "Ant-style exclude glob(s), comma- or space-separated."),
            ParamSpec.optional(
                "allowEmptyArchive",
                "boolean",
                "If true, a step that matches no files succeeds; "
                    + "otherwise it fails (default false)."),
            ParamSpec.optional(
                "fingerprint",
                "boolean",
                "If true, record a content fingerprint for each archived file "
                    + "(default false)."),
            ParamSpec.optional("caseSensitive", "boolean", "Glob case sensitivity (default true)."),
            ParamSpec.optional(
                "followSymlinks",
                "boolean",
                "Follow symbolic links while scanning (default true).")));
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    // Accept both the explicit `artifacts:` key and the scalar shorthand
    // `archiveArtifacts: '<glob>'` (which the parser stores under `value`).
    String includes = request.argString("artifacts");
    if (includes == null || includes.isBlank()) {
      includes = request.argString("value");
    }
    if (includes == null || includes.isBlank()) {
      return StepResult.failed("archiveArtifacts: 'artifacts' is required");
    }
    boolean allowEmptyArchive = request.argBoolean("allowEmptyArchive", false);
    boolean fingerprint = request.argBoolean("fingerprint", false);
    boolean caseSensitive = request.argBoolean("caseSensitive", true);
    boolean followSymlinks = request.argBoolean("followSymlinks", true);
    String excludes = request.argString("excludes");

    File baseDir = request.workDir().toFile();
    if (!baseDir.isDirectory()) {
      return StepResult.failed("archiveArtifacts: workspace directory does not exist: " + baseDir);
    }

    DirectoryScanner scanner = new DirectoryScanner();
    scanner.setBasedir(baseDir);
    scanner.setIncludes(splitPatterns(includes));
    String[] excludePatterns = splitPatterns(excludes);
    if (excludePatterns.length > 0) {
      scanner.setExcludes(excludePatterns);
    }
    scanner.setCaseSensitive(caseSensitive);
    scanner.setFollowSymlinks(followSymlinks);
    scanner.scan();
    String[] matched = scanner.getIncludedFiles();

    if (matched.length == 0) {
      String message = "archiveArtifacts: no files matched '" + includes + "'";
      if (allowEmptyArchive) {
        request.log().system(message + " — allowEmptyArchive is set, continuing");
        return StepResult.success();
      }
      return StepResult.failed(message);
    }

    for (String relative : matched) {
      // DirectoryScanner yields platform-separator paths; the archive name — the key the
      // artifact is browsed and downloaded by — is always forward-slash.
      String name = relative.replace(File.separatorChar, '/');
      Path file = request.workDir().resolve(relative);
      try {
        request.artifacts().archive(name, file, fingerprint);
      } catch (IOException e) {
        // Surface the underlying ArtifactSink failure to the build log AND the
        // step's terminal status (closes #848). Before this catch the IOException
        // bubbled up through TaskExecutor where it became a generic "step
        // archiveArtifacts threw: ..." line, but the failure reason — "no
        // artifact store is configured" / "stored artifact but failed to record
        // it" / "not a readable file" — never made it into the streamed step
        // log itself. Mirror TaskExecutor's cause-unwrap pattern so a wrapped
        // SQLException or IOException still names its real cause.
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String detail = "archiveArtifacts: failed to archive '" + name + "': " + cause.getMessage();
        request.log().system(detail);
        return StepResult.failed(detail);
      }
    }
    request
        .log()
        .system(
            "archiveArtifacts: stored "
                + matched.length
                + " file(s)"
                + (fingerprint ? " (fingerprinted)" : ""));
    return StepResult.success();
  }

  /**
   * Split a comma- / whitespace-separated Ant glob string into a pattern array. A {@code null} or
   * blank input yields an empty array (no patterns).
   */
  private static String[] splitPatterns(String raw) {
    if (raw == null || raw.isBlank()) {
      return new String[0];
    }
    return Arrays.stream(raw.split("[,\\s]+"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toArray(String[]::new);
  }
}
