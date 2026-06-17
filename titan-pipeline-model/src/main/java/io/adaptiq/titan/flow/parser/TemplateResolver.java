package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a {@code use: { from: <path> }} reference (design/56) to the template file's textual
 * content. v1 supports only local, repo-relative paths; the default implementation, {@link
 * LocalRelativeTemplateResolver}, reads from a configured workspace root.
 *
 * <p>The resolver is a small interface so test fixtures can stub a synthetic filesystem without
 * writing to disk — every {@code TemplateScopeTest} case constructs an in-memory map of {@code Path
 * → content} via {@link InMemoryTemplateResolver}. The production path is a real filesystem read
 * against a configured base directory.
 *
 * <p>v1 hard rules: {@code from:} must be a relative path; absolute paths and paths with a URI
 * scheme (e.g. {@code http://}, {@code github.com/...}) are rejected. Resolution may use {@code
 * ../} traversal, but the resolved path must stay inside the configured base directory (the repo
 * root). Anything escaping that root is a parse error.
 */
public interface TemplateResolver {

  /**
   * Resolve a {@code from:} value to the template's text. Implementations MUST enforce the v1 rules
   * documented on the interface — the parser calls this once per {@code use:} block and propagates
   * a {@link PipelineParseException} on every failure mode.
   *
   * @param from the literal {@code from:} value from the YAML — never {@code null} or blank.
   * @param errorContext a located prefix the implementation prepends to any thrown {@link
   *     PipelineParseException} (e.g. {@code "stage 'build' use"}).
   * @return the template file's text content as a UTF-8 string.
   * @throws PipelineParseException when {@code from:} is malformed, escapes the base directory, or
   *     names a file that does not exist / cannot be read.
   */
  @NonNull
  TemplateContent resolve(@NonNull String from, @NonNull String errorContext);

  /**
   * The resolved template — its source path (for error messages and recursive-{@code use:}
   * detection) and its UTF-8 text body.
   */
  record TemplateContent(@NonNull String resolvedPath, @NonNull String text) {}

  /**
   * The production resolver: reads template files from disk, relative to a configured base
   * directory (the pipeline file's directory). Enforces every v1 rule — relative-only, no scheme,
   * no escape from the base.
   */
  final class LocalRelativeTemplateResolver implements TemplateResolver {

    @NonNull private final Path baseDir;

    public LocalRelativeTemplateResolver(@NonNull Path baseDir) {
      // Canonicalise the base once — `resolve()` compares the canonical resolved path against it
      // to detect repo-root escapes. `toAbsolutePath().normalize()` is enough — we do not require
      // the base to exist (a test fixture may stub a non-existent baseDir; the file-existence
      // check below catches the real missing-template case with a clear error).
      this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    @NonNull
    public TemplateContent resolve(@NonNull String from, @NonNull String errorContext) {
      validateForm(from, errorContext);
      Path resolved = baseDir.resolve(from).normalize();
      if (!resolved.startsWith(baseDir)) {
        throw new PipelineParseException(
            errorContext
                + ": template path '"
                + from
                + "' escapes the pipeline base directory ("
                + baseDir
                + ")");
      }
      if (!Files.exists(resolved)) {
        throw new PipelineParseException(
            errorContext
                + ": template file does not exist at resolved path '"
                + resolved
                + "' (from='"
                + from
                + "')");
      }
      if (!Files.isRegularFile(resolved)) {
        throw new PipelineParseException(
            errorContext + ": template path '" + resolved + "' is not a regular file");
      }
      String text;
      try {
        text = Files.readString(resolved, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new PipelineParseException(
            errorContext + ": failed to read template '" + resolved + "': " + e.getMessage(), e);
      }
      return new TemplateContent(resolved.toString(), text);
    }

    static void validateForm(@NonNull String from, @NonNull String errorContext) {
      if (from.isBlank()) {
        throw new PipelineParseException(errorContext + ": 'from' must be a non-blank string");
      }
      // No URI scheme — `http://`, `https://`, `github.com/...`, `file://`, …
      // Detect scheme: a colon early in the string that is not a Windows drive letter (out of
      // v1 scope — we are on Linux/macOS dev rigs; reject any `<scheme>:` prefix uniformly).
      int colon = from.indexOf(':');
      if (colon >= 0) {
        throw new PipelineParseException(
            errorContext
                + ": 'from' must be a local relative path — schemes like 'http://', "
                + "'github.com/...' or any '<x>:' prefix are not supported in v1 "
                + "(use 'libraries:' for remote reuse, design/53). Got: '"
                + from
                + "'");
      }
      if (from.startsWith("/")) {
        throw new PipelineParseException(
            errorContext + ": 'from' must be a relative path, not absolute. Got: '" + from + "'");
      }
      if (from.startsWith("~")) {
        throw new PipelineParseException(
            errorContext
                + ": 'from' must be a relative path, not '~'-expanded. Got: '"
                + from
                + "'");
      }
    }
  }
}
