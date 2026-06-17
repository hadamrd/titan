package io.adaptiq.titan.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;

/**
 * The Bitbucket-Cloud-specific facts a PR reporter needs, projected out of a build's {@code
 * triggerMetaJson} (issue #1117). All four scalars are required; any missing piece means the build
 * did not originate from a Bitbucket PR webhook and the reporter silently skips.
 *
 * @param workspace Bitbucket workspace slug.
 * @param repoSlug repository slug.
 * @param prId pull-request id (Bitbucket calls it {@code pullrequest.id}).
 * @param credentialsId key into the credentials store, scope {@code bitbucket-webhook}.
 */
public record BitbucketPrContext(
    @NonNull String workspace, @NonNull String repoSlug, int prId, @NonNull String credentialsId) {

  /**
   * Extract from {@code triggerMetaJson}. Accepts {@code prId} or {@code prNumber} for the PR id
   * (the inbound webhook ticket #1079 may serialise either). Returns {@code null} when any required
   * field is missing or the JSON is unparseable — the reporter treats that as "not a Bitbucket PR
   * build".
   */
  @Nullable
  public static BitbucketPrContext extract(
      @Nullable String triggerMetaJson,
      @NonNull com.fasterxml.jackson.databind.ObjectMapper mapper) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = mapper.readTree(triggerMetaJson);
      String ws = node.path("workspace").asText("");
      String repo = node.path("repoSlug").asText("");
      String credId = node.path("bitbucketCredentialsId").asText("");
      int prId = node.path("prId").asInt(node.path("prNumber").asInt(0));
      if (ws.isEmpty() || repo.isEmpty() || credId.isEmpty() || prId <= 0) {
        return null;
      }
      return new BitbucketPrContext(ws, repo, prId, credId);
    } catch (IOException e) {
      return null;
    }
  }

  /** Base REST path for this PR's comment collection. */
  @NonNull
  public String commentsPath() {
    return "/2.0/repositories/"
        + workspace
        + "/"
        + repoSlug
        + "/pullrequests/"
        + prId
        + "/comments";
  }
}
