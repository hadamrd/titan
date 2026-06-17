package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only {@link IncludeResolver} backed by an in-memory map of {@code sourceId → text} — mirrors
 * {@code InMemoryTemplateResolver} (design/56). Supports both local and cross-repo lookups by
 * composing the relevant identifier as the map key:
 *
 * <ul>
 *   <li>local: the key is the literal {@code from:} value;
 *   <li>cross-repo: the key is {@code repo@ref:path}.
 * </ul>
 */
public final class InMemoryIncludeResolver implements IncludeResolver {

  @NonNull private final Map<String, String> contents;

  public InMemoryIncludeResolver() {
    this.contents = new LinkedHashMap<>();
  }

  @NonNull
  public InMemoryIncludeResolver put(@NonNull String key, @NonNull String text) {
    contents.put(key, text);
    return this;
  }

  @Override
  @NonNull
  public ResolvedInclude resolveLocal(@NonNull String from, @NonNull String errorContext) {
    String text = contents.get(from);
    if (text == null) {
      throw new PipelineParseException(
          errorContext + ": no in-memory include for key '" + from + "'");
    }
    return new ResolvedInclude(from, text);
  }

  @Override
  @NonNull
  public ResolvedInclude resolveRepo(
      @NonNull String repo,
      @NonNull String ref,
      @NonNull String pathInRepo,
      @Nullable String credential,
      @NonNull String errorContext) {
    String key = repo + "@" + ref + ":" + pathInRepo;
    String text = contents.get(key);
    if (text == null) {
      throw new PipelineParseException(
          errorContext + ": no in-memory cross-repo include for key '" + key + "'");
    }
    return new ResolvedInclude(key, text);
  }
}
