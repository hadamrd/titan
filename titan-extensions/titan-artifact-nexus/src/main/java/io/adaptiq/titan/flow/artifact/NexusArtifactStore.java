package io.adaptiq.titan.flow.artifact;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An {@link ArtifactStore} backed by a Nexus 3 <em>raw</em> hosted repository (design/49). A raw
 * repo stores arbitrary files addressed by path and serves them over plain HTTP at {@code
 * {url}/repository/{repository}/{path}} — the only Nexus format fit for opaque build blobs.
 *
 * <p><strong>Reachability.</strong> Like the S3 backend and unlike {@link FilesystemArtifactStore},
 * this store needs no shared volume — Nexus is reachable over HTTP(S), so workers and the
 * controller talk to it independently. Many orgs already run Nexus for Maven/npm/Docker; this
 * reuses it.
 *
 * <p><strong>No HTTP dependency (D3).</strong> Nexus raw is plain HTTP with HTTP Basic auth, so the
 * JDK {@link HttpClient} covers it — nothing beyond the JDK is on the classpath.
 *
 * <p><strong>Layout (D5).</strong> The {@code storageRef} is the repo-relative path {@code
 * <buildId>/<kind>/<name>} — the same layout as the {@code fs} and {@code s3} stores. It is opaque
 * to callers, persisted verbatim in {@code titan.artifact.storage_ref}.
 *
 * <p><strong>{@code put} buffers to a temp file (D4).</strong> {@link #put} streams the content
 * once through a {@link DigestInputStream} into a JVM temp file — computing the SHA-256 and the
 * size in that single pass, exactly as the {@code fs} and {@code s3} stores do — then issues one
 * HTTP {@code PUT} of the file, then deletes the temp file. A {@code PUT} to an existing raw path
 * overwrites, so a re-run of the producing step converges.
 *
 * <p><strong>{@code open}/{@code delete} (D6).</strong> {@code open} is a {@code GET} (404 → {@link
 * java.util.Optional#empty()}); {@code delete} is a {@code DELETE} (404 → {@code false}).
 *
 * <p><strong>{@code pruneStashes} / {@code deleteBuild} (D7).</strong> Raw repos expose no prefix
 * listing on the content path, so both list components via the Nexus components REST API ({@code
 * GET /service/rest/v1/components?repository=...}, paginated by {@code continuationToken}), keep
 * the components whose asset path starts with the build prefix ({@code <buildId>/STASH/} for a
 * stash prune, the whole {@code <buildId>/} for a build delete), and issue a {@code DELETE
 * /service/rest/v1/components/{id}} for each.
 */
public final class NexusArtifactStore implements ArtifactStore {

  private static final String KIND = "nexus";

  /** Naive extractors for the small, well-formed JSON the components API returns. */
  private static final Pattern CONTINUATION_TOKEN =
      Pattern.compile("\"continuationToken\"\\s*:\\s*(?:\"([^\"]*)\"|null)");

  private static final Pattern COMPONENT_ID = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]*)\"");
  private static final Pattern ASSET_PATH = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]*)\"");

  private final HttpClient http;
  private final String baseUrl;
  private final String repository;
  private final String authHeader;

  /**
   * @param baseUrl the Nexus base URL, e.g. {@code https://nexus.example.com} — no trailing slash
   * @param repository the raw hosted repository name
   * @param username the Nexus user
   * @param password the Nexus password
   */
  public NexusArtifactStore(
      @NonNull String baseUrl,
      @NonNull String repository,
      @NonNull String username,
      @NonNull String password) {
    this.http = HttpClient.newHttpClient();
    this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    this.repository = repository;
    this.authHeader =
        "Basic "
            + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String kind() {
    return KIND;
  }

  @Override
  public StoredBlob put(@NonNull ArtifactKey key, @NonNull InputStream content) throws IOException {
    String ref = key.buildId() + "/" + key.kind() + "/" + key.name().replace('\\', '/');

    // D4: stream once into a temp file, folding a SHA-256 digest into the same pass. An HTTP
    // PUT needs a known content length, which the InputStream does not carry.
    MessageDigest digest = sha256();
    Path tmp = Files.createTempFile(".titan-art-", ".tmp");
    long size;
    try {
      try (DigestInputStream in = new DigestInputStream(content, digest);
          OutputStream out = Files.newOutputStream(tmp)) {
        size = in.transferTo(out);
      }
      HttpResponse<Void> resp =
          send(
              HttpRequest.newBuilder(contentUri(ref)).PUT(HttpRequest.BodyPublishers.ofFile(tmp)),
              HttpResponse.BodyHandlers.discarding());
      int sc = resp.statusCode();
      if (sc < 200 || sc >= 300) {
        throw new IOException("failed to put artifact to nexus path '" + ref + "': HTTP " + sc);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted putting artifact to nexus path '" + ref + "'", e);
    } finally {
      Files.deleteIfExists(tmp);
    }
    return new StoredBlob(size, HexFormat.of().formatHex(digest.digest()), ref);
  }

  @Override
  public java.util.Optional<InputStream> open(@NonNull String storageRef) throws IOException {
    try {
      HttpResponse<InputStream> resp =
          send(
              HttpRequest.newBuilder(contentUri(storageRef)).GET(),
              HttpResponse.BodyHandlers.ofInputStream());
      int sc = resp.statusCode();
      if (sc == 404) {
        resp.body().close();
        return java.util.Optional.empty();
      }
      if (sc < 200 || sc >= 300) {
        resp.body().close();
        throw new IOException("failed to open nexus path '" + storageRef + "': HTTP " + sc);
      }
      return java.util.Optional.of(resp.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted opening nexus path '" + storageRef + "'", e);
    }
  }

  @Override
  public boolean delete(@NonNull String storageRef) throws IOException {
    try {
      HttpResponse<Void> resp =
          send(
              HttpRequest.newBuilder(contentUri(storageRef)).DELETE(),
              HttpResponse.BodyHandlers.discarding());
      int sc = resp.statusCode();
      if (sc == 404) {
        return false;
      }
      if (sc < 200 || sc >= 300) {
        throw new IOException("failed to delete nexus path '" + storageRef + "': HTTP " + sc);
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted deleting nexus path '" + storageRef + "'", e);
    }
  }

  @Override
  public void pruneStashes(long buildId) throws IOException {
    deleteComponentsUnder(buildId + "/" + ArtifactKey.STASH + "/", buildId);
  }

  @Override
  public void deleteBuild(long buildId) throws IOException {
    // Layout is <buildId>/<kind>/<name>, so the whole build is one prefix.
    deleteComponentsUnder(buildId + "/", buildId);
  }

  /**
   * Delete every component whose asset path lies under {@code prefix}. Raw repos expose no prefix
   * listing on the content path (D7), so this lists components via the REST API and filters.
   */
  private void deleteComponentsUnder(String prefix, long buildId) throws IOException {
    try {
      String continuation = null;
      do {
        String url =
            baseUrl
                + "/service/rest/v1/components?repository="
                + URLEncoder.encode(repository, StandardCharsets.UTF_8);
        if (continuation != null) {
          url += "&continuationToken=" + URLEncoder.encode(continuation, StandardCharsets.UTF_8);
        }
        HttpResponse<String> resp =
            send(
                HttpRequest.newBuilder(URI.create(url)).GET(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int sc = resp.statusCode();
        if (sc < 200 || sc >= 300) {
          throw new IOException(
              "failed to list nexus components for build " + buildId + ": HTTP " + sc);
        }
        String body = resp.body();
        for (String component : splitComponents(body)) {
          if (componentMatchesPrefix(component, prefix)) {
            deleteComponent(extractFirst(COMPONENT_ID, component));
          }
        }
        continuation = extractContinuationToken(body);
      } while (continuation != null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted reaping nexus components for build " + buildId, e);
    }
  }

  private void deleteComponent(String componentId) throws IOException, InterruptedException {
    if (componentId == null) {
      return;
    }
    String url =
        baseUrl
            + "/service/rest/v1/components/"
            + URLEncoder.encode(componentId, StandardCharsets.UTF_8);
    HttpResponse<Void> resp =
        send(
            HttpRequest.newBuilder(URI.create(url)).DELETE(),
            HttpResponse.BodyHandlers.discarding());
    int sc = resp.statusCode();
    if (sc != 404 && (sc < 200 || sc >= 300)) {
      throw new IOException("failed to delete nexus component '" + componentId + "': HTTP " + sc);
    }
  }

  private <T> HttpResponse<T> send(HttpRequest.Builder builder, HttpResponse.BodyHandler<T> handler)
      throws IOException, InterruptedException {
    return http.send(builder.header("Authorization", authHeader).build(), handler);
  }

  /** The content URI for a repo-relative path: {@code {url}/repository/{repository}/{path}}. */
  private URI contentUri(String storageRef) {
    StringBuilder sb = new StringBuilder(baseUrl).append("/repository/").append(repository);
    for (String segment : storageRef.split("/")) {
      if (!segment.isEmpty()) {
        sb.append('/')
            .append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
      }
    }
    return URI.create(sb.toString());
  }

  /**
   * Split the {@code "items": [ {...}, {...} ]} array of a components-API page into the raw JSON
   * text of each component object. The components API returns small, well-formed JSON; a brace scan
   * is enough and avoids pulling in a JSON parser dependency (D3 — zero non-JDK deps).
   */
  private static java.util.List<String> splitComponents(String body) {
    java.util.List<String> out = new java.util.ArrayList<>();
    int items = body.indexOf("\"items\"");
    if (items < 0) {
      return out;
    }
    int arrayStart = body.indexOf('[', items);
    if (arrayStart < 0) {
      return out;
    }
    int depth = 0;
    int objStart = -1;
    boolean inString = false;
    boolean escaped = false;
    for (int i = arrayStart; i < body.length(); i++) {
      char c = body.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
      } else if (c == '{') {
        if (depth == 0) {
          objStart = i;
        }
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0 && objStart >= 0) {
          out.add(body.substring(objStart, i + 1));
          objStart = -1;
        }
      } else if (c == ']' && depth == 0) {
        break;
      }
    }
    return out;
  }

  /** True if any asset of this component has a path under {@code prefix}. */
  private static boolean componentMatchesPrefix(String component, String prefix) {
    Matcher m = ASSET_PATH.matcher(component);
    while (m.find()) {
      String path = m.group(1);
      if (path != null && (path.startsWith(prefix) || path.startsWith("/" + prefix))) {
        return true;
      }
    }
    return false;
  }

  private static String extractContinuationToken(String body) {
    Matcher m = CONTINUATION_TOKEN.matcher(body);
    if (m.find()) {
      String v = m.group(1);
      return (v == null || v.isEmpty()) ? null : v;
    }
    return null;
  }

  private static String extractFirst(Pattern p, String text) {
    Matcher m = p.matcher(text);
    return m.find() ? m.group(1) : null;
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable in this JVM", e); // never happens
    }
  }
}
