package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.GithubRepositoryRow;
import java.util.List;

/** Wire shape of one repo under a {@link GithubInstallationDto}, with its discovered pipelines. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GithubRepoDto(
    @NonNull String fullName,
    @NonNull String defaultBranch,
    @NonNull String htmlUrl,
    @NonNull List<DiscoveredPipelineDto> pipelines) {

  @NonNull
  public static GithubRepoDto from(
      @NonNull GithubRepositoryRow row, @NonNull List<DiscoveredPipelineDto> pipelines) {
    String full = row.owner + "/" + row.name;
    String branch = row.defaultBranch != null ? row.defaultBranch : "main";
    String html = "https://github.com/" + full;
    return new GithubRepoDto(full, branch, html, pipelines);
  }
}
