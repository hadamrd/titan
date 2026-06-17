package io.adaptiq.titan.scm.github;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Probot-shape DTO for the GitHub App manifest-callback response (issue #873).
 *
 * <p>Mirrors the JSON GitHub returns from {@code POST /app-manifests/{code}/conversions} — see <a
 * href="https://docs.github.com/en/apps/sharing-github-apps/registering-a-github-app-from-a-manifest">
 * GitHub's manifest docs</a> and the Probot <a
 * href="https://github.com/probot/probot/blob/master/src/types.ts">type definitions</a>. We carry
 * every field GitHub currently documents so the value survives deserialization even if no call-site
 * reads it yet; downstream consumers (OAuth-on-behalf-of, install permissions UI, …) can pick
 * fields up later without a schema PR.
 *
 * <p>Unknown fields are silently ignored via {@link GithubJson}'s mapper — adding a new top-level
 * property on GitHub's side will <strong>not</strong> 500 the manifest-callback endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class GithubManifestCallback {

  /** App's numeric id — required; kohsuke's {@code GHAppFromManifest.getId()} equivalent. */
  public long id;

  /** Short URL slug, e.g. {@code "titan-ci"}. */
  @Nullable public String slug;

  /** Human-readable App name. */
  @Nullable public String name;

  /** {@code https://github.com/apps/<slug>}. */
  @Nullable public String htmlUrl;

  /**
   * PKCS#1 or PKCS#8 PEM private key. Single-use: returned exactly once on manifest exchange; Titan
   * envelope-encrypts it immediately ({@link GithubAppService#handleManifestCallback}).
   */
  @Nullable public String pem;

  /** Webhook HMAC secret. Single-use, envelope-encrypted on receipt. */
  @Nullable public String webhookSecret;

  /** OAuth client id (new field GitHub started returning; tolerated since #873). */
  @Nullable public String clientId;

  /** OAuth client secret. Sensitive — not yet persisted, but no longer dropped on parse. */
  @Nullable public String clientSecret;

  /** {@code node_id} — GraphQL global id. */
  @Nullable public String nodeId;

  /**
   * App owner (user/org). Probot maps this onto {@code SimpleAccount}; we keep the raw map so a
   * future schema change on GitHub's side does not require a code edit here.
   */
  @Nullable public Map<String, Object> owner;

  /** Default install permissions, e.g. {@code {"contents":"read","checks":"write"}}. */
  @Nullable public Map<String, String> permissions;

  /** Webhook events the App subscribes to, e.g. {@code ["push","pull_request"]}. */
  @Nullable public List<String> events;

  /** ISO-8601 timestamp. */
  @Nullable public String createdAt;

  /** ISO-8601 timestamp. */
  @Nullable public String updatedAt;

  /** Optional install count surfaced by GitHub. */
  @Nullable public Integer installationsCount;

  /** External-services URL (per docs). */
  @Nullable public String externalUrl;

  /** Optional public/private flag from the manifest. */
  @Nullable public Boolean publicApp;

  /** App description. */
  @Nullable public String description;

  public GithubManifestCallback() {}
}
