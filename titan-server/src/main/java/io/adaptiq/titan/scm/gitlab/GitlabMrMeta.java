package io.adaptiq.titan.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;

/**
 * The scalar facts the GitLab merge-request reporters ({@link GitlabMrCommentReporter} and {@link
 * GitlabMrReviewReporter}) need out of the {@code trigger_meta_json} the GitLab webhook serialized
 * (issue #1168).
 *
 * <p>This mirrors {@link GitlabStatusReporter.Meta} but adds {@code mrIid} — the project-scoped
 * merge-request internal id the notes / discussions API addresses. The webhook ({@code
 * GitlabWebhookApi.buildTriggerMetaJson}) emits {@code mrIid} <em>only</em> for Merge Request Hook
 * deliveries, so push / tag-push / manual / cron builds parse to {@code null} here and the
 * reporters no-op.
 *
 * <p>Required extraction: a build is "an MR build the reporter can address" only when {@code
 * mrIid}, {@code projectId} and {@code gitlabCredentialsId} are all present. Any missing piece →
 * {@code null} → silent (FINE-level) skip in the caller.
 *
 * <p>{@code commitSha} is deliberately <em>optional</em> (issue #1168 review): neither {@link
 * GitlabMrCommentReporter} (notes) nor {@link GitlabMrReviewReporter} (discussions — it resolves
 * the diff SHAs from the MR's own {@code diff_refs}) reads {@code commitSha}, so gating extraction
 * on it would no-op an otherwise perfectly addressable MR build whose meta happened to omit the
 * sha. It is still parsed and surfaced ({@code null} when absent) for parity with {@link
 * GitlabStatusReporter.Meta} and possible future use.
 */
final class GitlabMrMeta {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  final long mrIid;
  final long projectId;
  @Nullable final String commitSha;
  @NonNull final String credentialsId;

  private GitlabMrMeta(
      long mrIid, long projectId, @Nullable String commitSha, @NonNull String credentialsId) {
    this.mrIid = mrIid;
    this.projectId = projectId;
    this.commitSha = commitSha;
    this.credentialsId = credentialsId;
  }

  /**
   * Parse the required scalars out of {@code triggerMetaJson}. Returns {@code null} when the JSON
   * is blank, unparseable, or any of {@code mrIid} / {@code projectId} / {@code
   * gitlabCredentialsId} is missing / non-positive — the callers treat {@code null} as "not an
   * addressable MR build, no-op". {@code commitSha} is optional and left {@code null} when absent.
   */
  @Nullable
  static GitlabMrMeta extract(@Nullable String triggerMetaJson) {
    if (triggerMetaJson == null || triggerMetaJson.isBlank()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(triggerMetaJson);
      long mrIid = node.path("mrIid").asLong(0L);
      long projectId = node.path("projectId").asLong(0L);
      String sha = node.path("commitSha").asText("");
      String credId = node.path("gitlabCredentialsId").asText("");
      if (mrIid <= 0L || projectId <= 0L || credId.isEmpty()) {
        return null;
      }
      return new GitlabMrMeta(mrIid, projectId, sha.isEmpty() ? null : sha, credId);
    } catch (IOException e) {
      return null;
    }
  }
}
