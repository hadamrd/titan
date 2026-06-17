package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.GithubInstallationRow;
import java.time.Instant;
import java.util.List;

/**
 * Wire shape of one row in {@code titan.github_installations} (#832), enriched with the nested
 * repos + their discovered pipelines (#876). The UI's {@code IntegrationsGithubPage} renders one
 * card per installation and walks {@code repos[].pipelines[]} for the per-pipeline Enable button.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GithubInstallationDto(
    long id,
    long githubInstallationId,
    @NonNull String accountLogin,
    @NonNull String accountType,
    boolean suspended,
    @NonNull Instant createdAt,
    @NonNull List<GithubRepoDto> repos) {

  @NonNull
  public static GithubInstallationDto from(
      @NonNull GithubInstallationRow row, @NonNull List<GithubRepoDto> repos) {
    return new GithubInstallationDto(
        row.id,
        row.installId,
        row.accountLogin,
        row.accountType,
        row.suspendedAt != null,
        row.createdAt,
        repos);
  }
}
