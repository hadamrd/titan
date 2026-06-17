package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.GithubPipelineDiscoveredRow;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wire shape of one row in {@code titan.github_pipelines_discovered}, with the {@code
 * parsed_metadata} JSON unpacked into typed top-level fields ({@code stagesCount}, {@code
 * triggers}, {@code paramsCount}) so the UI does not re-parse on every render. {@code enabled} and
 * {@code jobId} are computed by joining against {@code titan.jobs} at request time.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveredPipelineDto(
    @NonNull String filename,
    @NonNull String name,
    int stagesCount,
    @NonNull List<String> triggers,
    int paramsCount,
    boolean enabled,
    @Nullable Long jobId) {

  private static final Logger LOG = Logger.getLogger(DiscoveredPipelineDto.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @NonNull
  public static DiscoveredPipelineDto from(
      @NonNull GithubPipelineDiscoveredRow row, boolean enabled, @Nullable Long jobId) {
    String name = displayNameFromFilename(row.filename);
    int stagesCount = 0;
    int paramsCount = 0;
    List<String> triggers = Collections.emptyList();
    if (row.parsedMetadata != null && !row.parsedMetadata.isBlank()) {
      try {
        JsonNode meta = MAPPER.readTree(row.parsedMetadata);
        JsonNode stages = meta.get("stages");
        if (stages != null && stages.isArray()) stagesCount = stages.size();
        JsonNode params = meta.get("parameters");
        if (params != null && params.isArray()) paramsCount = params.size();
        JsonNode trig = meta.get("triggers");
        if (trig != null && trig.isArray()) {
          triggers = new java.util.ArrayList<>(trig.size());
          for (JsonNode t : trig) {
            // triggers are stored as objects like {"type":"push","branch":"main"}; if it's a plain
            // string, take it; otherwise prefer its "type" field. UI just needs strings to chip.
            if (t.isTextual()) triggers.add(t.asText());
            else if (t.has("type")) triggers.add(t.get("type").asText());
          }
        }
        JsonNode parsedName = meta.get("name");
        if (parsedName != null && parsedName.isTextual()) name = parsedName.asText();
      } catch (Exception e) {
        LOG.log(Level.FINE, "could not parse pipeline parsed_metadata for " + row.filename, e);
      }
    }
    return new DiscoveredPipelineDto(
        row.filename, name, stagesCount, triggers, paramsCount, enabled, jobId);
  }

  @NonNull
  private static String displayNameFromFilename(@NonNull String filename) {
    // ".titan/pipelines/simple-build.yml" → "simple-build"
    String f = filename;
    int slash = f.lastIndexOf('/');
    if (slash >= 0) f = f.substring(slash + 1);
    if (f.endsWith(".yml")) f = f.substring(0, f.length() - 4);
    if (f.endsWith(".yaml")) f = f.substring(0, f.length() - 5);
    return f;
  }
}
