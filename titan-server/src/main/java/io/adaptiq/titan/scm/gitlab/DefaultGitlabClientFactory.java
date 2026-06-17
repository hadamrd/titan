package io.adaptiq.titan.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link GitlabClientFactory} backed by {@link HttpClient} (issue #1134).
 *
 * <p>One concrete impl, mapped fields → {@link GitlabEventDto}. The token + base URL come from
 * boot-time wiring (the host instantiates this once per registered GitLab project; the secret never
 * leaves Settings → constructor). Per Manifesto §"Security": the token is never logged.
 */
public final class DefaultGitlabClientFactory implements GitlabClientFactory {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

  private final HttpClient http;
  private final String baseUrl;
  private final String privateToken;
  private final Duration requestTimeout;

  public DefaultGitlabClientFactory(
      @NonNull HttpClient http, @NonNull String baseUrl, @NonNull String privateToken) {
    this(http, baseUrl, privateToken, DEFAULT_REQUEST_TIMEOUT);
  }

  public DefaultGitlabClientFactory(
      @NonNull HttpClient http,
      @NonNull String baseUrl,
      @NonNull String privateToken,
      @NonNull Duration requestTimeout) {
    if (baseUrl.isEmpty()) {
      throw new IllegalArgumentException("baseUrl must not be empty");
    }
    // The constructor refuses an empty token at boot — fail loud (Manifesto §"Boot-time
    // validation") rather than discover the auth gap on the first 401 mid-tick.
    if (privateToken.isEmpty()) {
      throw new IllegalArgumentException("privateToken must not be empty");
    }
    this.http = http;
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.privateToken = privateToken;
    this.requestTimeout = requestTimeout;
  }

  @Override
  @NonNull
  public PageResult listProjectEvents(
      @NonNull String projectId,
      @Nullable String sinceEventId,
      @Nullable String ifNoneMatch,
      int limit)
      throws GitlabApiException {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be > 0");
    }
    // GitLab's events endpoint supports `?after=YYYY-MM-DD&sort=asc&per_page=N`. We can't pass an
    // event-id cursor (the API has no such filter), so the caller post-filters by id; we rely on
    // ascending order + the dedupe store to drop replays. `action=pushed` keeps the page focused
    // on the events the reconcile loop cares about.
    String path =
        "/api/v4/projects/"
            + urlEncode(projectId)
            + "/events?action=pushed&sort=asc&per_page="
            + Math.min(limit, 100);

    HttpRequest.Builder reqBuilder =
        HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + path))
            .timeout(requestTimeout)
            .header("PRIVATE-TOKEN", privateToken)
            .header("Accept", "application/json")
            .GET();
    if (ifNoneMatch != null && !ifNoneMatch.isEmpty()) {
      reqBuilder.header("If-None-Match", ifNoneMatch);
    }

    HttpResponse<byte[]> response;
    try {
      response = http.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new GitlabApiException(
          "I/O failure calling GitLab events endpoint: " + e.getMessage(), -1, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GitlabApiException("interrupted calling GitLab events endpoint", -1, e);
    }

    int status = response.statusCode();
    String etag = response.headers().firstValue("ETag").orElse(null);
    if (status == 304) {
      return new PageResult(List.of(), ifNoneMatch != null ? ifNoneMatch : etag, true);
    }
    if (status == 401 || status == 403) {
      throw new GitlabApiException("GitLab auth failure (HTTP " + status + ")", status, null);
    }
    if (status >= 400) {
      throw new GitlabApiException("GitLab API returned HTTP " + status, status, null);
    }

    List<GitlabEventDto> events = parseEvents(response.body(), sinceEventId);
    return new PageResult(events, etag, false);
  }

  // ── parse ──────────────────────────────────────────────────────────────────

  @NonNull
  private static List<GitlabEventDto> parseEvents(
      @NonNull byte[] body, @Nullable String sinceEventId) throws GitlabApiException {
    JsonNode root;
    try {
      root = MAPPER.readTree(body);
    } catch (IOException e) {
      throw new GitlabApiException(
          "GitLab events response was not valid JSON: " + e.getMessage(), 200, e);
    }
    if (root == null || !root.isArray()) {
      throw new GitlabApiException("GitLab events response was not a JSON array", 200, null);
    }
    List<GitlabEventDto> out = new ArrayList<>(root.size());
    boolean past = sinceEventId == null;
    for (JsonNode node : root) {
      Optional<GitlabEventDto> parsed = parseOne(node);
      if (parsed.isEmpty()) {
        continue;
      }
      GitlabEventDto e = parsed.get();
      if (!past) {
        // The cursor filter: skip until we walk past the last-seen id, then emit everything after.
        if (e.eventId().equals(sinceEventId)) {
          past = true;
        }
        continue;
      }
      // Skip the cursor row itself — only strictly-newer events are returned.
      if (e.eventId().equals(sinceEventId)) {
        continue;
      }
      out.add(e);
    }
    return out;
  }

  @NonNull
  private static Optional<GitlabEventDto> parseOne(@NonNull JsonNode node) {
    if (!node.isObject()) {
      return Optional.empty();
    }
    JsonNode idNode = node.get("id");
    if (idNode == null || (!idNode.isNumber() && !idNode.isTextual())) {
      return Optional.empty();
    }
    String id = idNode.asText();
    String action = node.path("action_name").asText("");
    Instant occurredAt = parseInstant(node.path("created_at").asText(""));
    if (occurredAt == null) {
      return Optional.empty();
    }
    JsonNode push = node.path("push_data");
    String ref = push.path("ref").asText(null);
    String commit = push.path("commit_to").asText(null);
    byte[] raw = node.toString().getBytes(StandardCharsets.UTF_8);
    String eventType = action.isEmpty() ? "push" : action;
    return Optional.of(new GitlabEventDto(id, eventType, occurredAt, ref, commit, raw));
  }

  @Nullable
  private static Instant parseInstant(@NonNull String raw) {
    if (raw.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(raw);
    } catch (DateTimeParseException ignored) {
      // GitLab sometimes serializes with no timezone and a space separator. We fail-open by
      // skipping rows we cannot timestamp — the dedupe store still protects against replays.
      return null;
    }
  }

  @NonNull
  private static String stripTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  @NonNull
  private static String urlEncode(@NonNull String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
