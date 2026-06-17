package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a top-level {@code include:} reference (issue #1120) to the included YAML's textual
 * content. Two reference shapes — both expressed at the grammar level:
 *
 * <ul>
 *   <li>a bare relative string — the path to a sibling YAML file in this repo;
 *   <li>an object {@code { repo, ref, path, credential? }} — a fragment from another repo at a
 *       given ref. Cross-repo fetching is delegated to an implementation (typically backed by
 *       {@link LibraryFetcher}); a local-only deployment supplies an implementation that rejects
 *       cross-repo references with a clear error.
 * </ul>
 *
 * <p>This interface intentionally mirrors {@link TemplateResolver}: the parser owns the YAML
 * grammar and the inlining/cycle logic, the resolver owns the I/O boundary. Tests stub a synthetic
 * filesystem via {@link InMemoryIncludeResolver}.
 */
public interface IncludeResolver {

  /**
   * Resolve a local (relative-path) include to its text. Implementations MUST reject paths that
   * escape the configured base directory and MUST surface a missing-file as a {@link
   * PipelineParseException} with a {@code from='...'} hint.
   *
   * @param from the literal relative path from the YAML — never {@code null}, never blank.
   * @param errorContext a located prefix the impl prepends to any thrown exception.
   * @return the included file's resolved path (for error / cycle tracking) and UTF-8 text.
   * @throws PipelineParseException on any failure (escapes base, missing file, IO).
   */
  @NonNull
  ResolvedInclude resolveLocal(@NonNull String from, @NonNull String errorContext);

  /**
   * Resolve a cross-repo include. Default implementation rejects — wire up a {@code
   * LibraryFetcherIncludeResolver} (or equivalent) to enable cross-repo includes on the controller.
   */
  @NonNull
  default ResolvedInclude resolveRepo(
      @NonNull String repo,
      @NonNull String ref,
      @NonNull String pathInRepo,
      @Nullable String credential,
      @NonNull String errorContext) {
    throw new PipelineParseException(
        errorContext
            + ": cross-repo include { repo: '"
            + repo
            + "', ref: '"
            + ref
            + "', path: '"
            + pathInRepo
            + "' } is not supported by this IncludeResolver "
            + "(wire up a fetcher-backed resolver to enable it).");
  }

  /**
   * The resolved include — its canonical source identifier (for error messages and cycle detection)
   * and its UTF-8 text body.
   */
  record ResolvedInclude(@NonNull String sourceId, @NonNull String text) {}

  /**
   * The default local-only resolver: reads sibling YAML files from disk relative to a configured
   * base directory (the pipeline file's directory). Cross-repo includes fail loud with the default
   * {@link #resolveRepo} message.
   */
  final class LocalRelativeIncludeResolver implements IncludeResolver {

    @NonNull private final Path baseDir;

    public LocalRelativeIncludeResolver(@NonNull Path baseDir) {
      this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    @NonNull
    public ResolvedInclude resolveLocal(@NonNull String from, @NonNull String errorContext) {
      validateForm(from, errorContext);
      Path resolved = baseDir.resolve(from).normalize();
      if (!resolved.startsWith(baseDir)) {
        throw new PipelineParseException(
            errorContext
                + ": include path '"
                + from
                + "' escapes the pipeline base directory ("
                + baseDir
                + ")");
      }
      if (!Files.exists(resolved)) {
        throw new PipelineParseException(
            errorContext
                + ": included file does not exist at resolved path '"
                + resolved
                + "' (from='"
                + from
                + "'). Check the path is repo-relative and the file is committed.");
      }
      if (!Files.isRegularFile(resolved)) {
        throw new PipelineParseException(
            errorContext + ": include path '" + resolved + "' is not a regular file");
      }
      String text;
      try {
        text = Files.readString(resolved, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new PipelineParseException(
            errorContext + ": failed to read include '" + resolved + "': " + e.getMessage(), e);
      }
      return new ResolvedInclude(resolved.toString(), text);
    }

    static void validateForm(@NonNull String from, @NonNull String errorContext) {
      if (from.isBlank()) {
        throw new PipelineParseException(
            errorContext + ": include path must be a non-blank string");
      }
      int colon = from.indexOf(':');
      if (colon >= 0) {
        throw new PipelineParseException(
            errorContext
                + ": include path must be local-relative — schemes like 'http://' or any "
                + "'<x>:' prefix are not supported (use the { repo, ref, path } object form "
                + "for cross-repo includes). Got: '"
                + from
                + "'");
      }
      if (from.startsWith("/")) {
        throw new PipelineParseException(
            errorContext + ": include path must be relative, not absolute. Got: '" + from + "'");
      }
      if (from.startsWith("~")) {
        throw new PipelineParseException(
            errorContext
                + ": include path must be relative, not '~'-expanded. Got: '"
                + from
                + "'");
      }
    }
  }
}
