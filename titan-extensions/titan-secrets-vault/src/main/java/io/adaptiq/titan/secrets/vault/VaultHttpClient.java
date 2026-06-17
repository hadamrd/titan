package io.adaptiq.titan.secrets.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thin HTTP client for the subset of the Vault API the Titan backend needs:
 *
 * <ul>
 *   <li>{@code POST /v1/auth/approle/login} — exchange role-id + secret-id for a client token
 *       (AppRole mode only).
 *   <li>{@code GET /v1/auth/token/lookup-self} — discover the token TTL for refresh scheduling.
 *   <li>{@code GET /v1/{mount}/data/{path}} — KV v2 read.
 * </ul>
 *
 * <h2>Auth modes</h2>
 *
 * <p>The client dispatches on {@link VaultConfig#authMode()}:
 *
 * <ul>
 *   <li>{@link VaultConfig.AuthMode#APPROLE} — lazy login on first call; token cached until {@code
 *       ttl/2}, then re-logs in. A 403 from a KV read invalidates the cache and triggers exactly
 *       one re-login + retry — covers the "token was revoked out of band" path.
 *   <li>{@link VaultConfig.AuthMode#TOKEN} — user-supplied token is used as-is. NO refresh path
 *       (the operator owns rotation); a 403 surfaces as a clear "vault token expired or invalid"
 *       error. No retry loop — re-logging in would require credentials we don't have.
 * </ul>
 *
 * <p><strong>Retries.</strong> One retry on 5xx (server-side flake); no retry on 4xx (auth /
 * not-found are not transient and must surface immediately).
 *
 * <p><strong>Logging.</strong> Only the request path + HTTP status are ever logged. The token, the
 * secret-id, and the response body are NEVER logged. For diagnostics the user-supplied token is
 * referenced by its first 4 characters only.
 */
final class VaultHttpClient {

  private static final Logger LOGGER = Logger.getLogger(VaultHttpClient.class.getName());

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final VaultConfig config;
  private final HttpClient http;

  /** Lock that serialises login + token-cache mutation. Resolves are NOT serialised. */
  private final ReentrantLock loginLock = new ReentrantLock();

  /** Cached token. {@code null} until the first successful login (APPROLE mode). */
  @Nullable private volatile String token;

  /** When the cached token must be refreshed (i.e. login-time + ttl/2). APPROLE mode only. */
  @Nullable private volatile Instant refreshAfter;

  VaultHttpClient(@NonNull VaultConfig config) {
    this(config, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
  }

  // Visible for test fixture customization (e.g. a slow-network mock); not used in prod.
  VaultHttpClient(@NonNull VaultConfig config, @NonNull HttpClient http) {
    this.config = config;
    this.http = http;
  }

  // ── public surface ─────────────────────────────────────────────────────────

  /**
   * Read a KV v2 secret. Returns {@link Optional#empty()} on 404 (path doesn't exist); throws
   * {@link VaultException} on any other non-2xx.
   *
   * <p>Path semantics: the caller passes the logical path WITHOUT the {@code data/} segment Vault
   * inserts for KV v2. E.g. caller path {@code foo/bar} becomes wire path {@code
   * /v1/{mount}/data/foo/bar}. If the caller's path already starts with the mount (e.g. {@code
   * secret/foo/bar}), that prefix is stripped.
   */
  @NonNull
  Optional<String> readKvV2(@NonNull String path, @NonNull String key) {
    String relPath = stripMountPrefix(path);
    HttpResponse<String> resp = doKvRead(relPath);
    if (resp.statusCode() == 404) {
      LOGGER.log(Level.FINE, "[titan] vault kv miss: path={0}", relPath);
      return Optional.empty();
    }
    if (resp.statusCode() == 403) {
      if (config.authMode() == VaultConfig.AuthMode.TOKEN) {
        // No re-login path — the user owns this token and we have no way to mint a new one.
        // Surface a clear, actionable error rather than retrying blindly.
        throw new VaultException(
            "vault token expired or invalid (403) — supplied VAULT_TOKEN (prefix="
                + tokenPrefix(config.vaultToken())
                + ") is not accepted by vault; rotate VAULT_TOKEN and restart");
      }
      // APPROLE or KUBERNETES: token may have been revoked. Drop the cache and try exactly once
      // more. For kubernetes mode this re-reads the SA token from disk (it may have rotated).
      LOGGER.log(Level.INFO, "[titan] vault 403 on kv read, re-logging in: path={0}", relPath);
      invalidateToken();
      resp = doKvRead(relPath);
      if (resp.statusCode() == 404) {
        return Optional.empty();
      }
    }
    if (resp.statusCode() / 100 != 2) {
      throw new VaultException(
          "vault kv read failed: path=" + relPath + " status=" + resp.statusCode());
    }
    try {
      JsonNode root = JSON.readTree(resp.body());
      JsonNode data = root.path("data").path("data");
      JsonNode value = data.get(key);
      if (value == null || value.isNull()) {
        return Optional.empty();
      }
      // KV values are arbitrary JSON; coerce to string for the SecretsBackend contract.
      return Optional.of(value.isTextual() ? value.asText() : value.toString());
    } catch (IOException e) {
      throw new VaultException("vault response parse failure: path=" + relPath, e);
    }
  }

  /**
   * Write (create or update) a KV v2 secret at {@code path}. The map's entries become the secret's
   * fields. KV v2 semantics: this REPLACES the data at the given path (i.e. fields not present in
   * {@code data} are removed from the new version), producing a new version. Callers that want
   * merge semantics must read the current version first and merge client-side.
   *
   * <p>Throws {@link VaultException} on any non-2xx. Path semantics match {@link #readKvV2}: any
   * leading {@code mount/} or {@code data/} prefix is stripped.
   *
   * <p><strong>Secret hygiene.</strong> Neither {@code data} contents nor response body are ever
   * logged.
   */
  void putSecret(@NonNull String path, @NonNull Map<String, String> data) {
    String relPath = stripMountPrefix(path);
    ObjectNode body = JSON.createObjectNode();
    ObjectNode dataNode = body.putObject("data");
    for (Map.Entry<String, String> e : data.entrySet()) {
      dataNode.put(e.getKey(), e.getValue());
    }
    HttpResponse<String> resp = doKvWrite(relPath, body.toString());
    if (resp.statusCode() == 403 && config.authMode() != VaultConfig.AuthMode.TOKEN) {
      LOGGER.log(Level.INFO, "[titan] vault 403 on kv write, re-logging in: path={0}", relPath);
      invalidateToken();
      resp = doKvWrite(relPath, body.toString());
    }
    if (resp.statusCode() / 100 != 2) {
      throw new VaultException(
          "vault kv write failed: path=" + safePath(relPath) + " status=" + resp.statusCode());
    }
  }

  /**
   * Delete a KV v2 secret at {@code path}.
   *
   * <p>Two modes:
   *
   * <ul>
   *   <li>{@code soft=true} (default at the SPI layer): {@code DELETE /v1/{mount}/data/{path}} —
   *       latest version's data is hidden; metadata + version history retained; {@code undelete} is
   *       possible. Subsequent reads return 404 (so {@link SecretsBackend#resolvePlaintext} sees
   *       empty), but the audit trail and version timestamps remain. This is the right default for
   *       a CI/CD secrets backend: deletion is reversible operationally, and a re-create produces a
   *       new version rather than reusing the old slot.
   *   <li>{@code soft=false}: {@code DELETE /v1/{mount}/metadata/{path}} — hard delete, removes ALL
   *       versions and the metadata row. Irreversible. Use only when the operator explicitly wants
   *       to purge.
   * </ul>
   *
   * <p>Idempotency: a 404 from Vault is treated as success — deleting an absent secret is a no-op
   * per the {@link SecretsBackend} contract.
   */
  void deleteSecret(@NonNull String path, boolean soft) {
    String relPath = stripMountPrefix(path);
    HttpResponse<String> resp = doKvDelete(relPath, soft);
    if (resp.statusCode() == 403 && config.authMode() != VaultConfig.AuthMode.TOKEN) {
      LOGGER.log(Level.INFO, "[titan] vault 403 on kv delete, re-logging in: path={0}", relPath);
      invalidateToken();
      resp = doKvDelete(relPath, soft);
    }
    if (resp.statusCode() == 404) {
      // SPI contract: idempotent. Absent secret = success.
      return;
    }
    if (resp.statusCode() / 100 != 2) {
      throw new VaultException(
          "vault kv delete failed: path="
              + safePath(relPath)
              + " soft="
              + soft
              + " status="
              + resp.statusCode());
    }
  }

  // ── token lifecycle ───────────────────────────────────────────────────────

  @NonNull
  private String ensureToken() {
    if (config.authMode() == VaultConfig.AuthMode.TOKEN) {
      // Direct user-supplied token; no login round-trip, no caching mutation.
      String t = config.vaultToken();
      if (t == null || t.isBlank()) {
        // Unreachable in practice — VaultConfig invariants guarantee non-blank vaultToken in TOKEN
        // mode — but the analyser can't see through the discriminator.
        throw new VaultException("vault token mode: vaultToken is null/blank (bug)");
      }
      return t;
    }
    String t = token;
    Instant r = refreshAfter;
    if (t != null && r != null && Instant.now().isBefore(r)) {
      return t;
    }
    loginLock.lock();
    try {
      // Re-check under the lock in case another thread just logged in.
      String cached = token;
      Instant cachedRefresh = refreshAfter;
      if (cached != null && cachedRefresh != null && Instant.now().isBefore(cachedRefresh)) {
        return cached;
      }
      login();
      String fresh = token;
      if (fresh == null) {
        // Defensive: login() either sets token or throws; this branch is unreachable but the
        // analyser can't see through the side effect.
        throw new VaultException("vault login completed without setting a token (bug)");
      }
      return fresh;
    } finally {
      loginLock.unlock();
    }
  }

  private void invalidateToken() {
    loginLock.lock();
    try {
      token = null;
      refreshAfter = null;
    } finally {
      loginLock.unlock();
    }
  }

  private void login() {
    VaultConfig.AuthMode mode = config.authMode();
    String endpoint;
    ObjectNode body = JSON.createObjectNode();
    if (mode == VaultConfig.AuthMode.KUBERNETES) {
      endpoint = "/v1/auth/kubernetes/login";
      String jwt = readServiceAccountToken();
      body.put("role", config.vaultRole());
      body.put("jwt", jwt);
    } else {
      endpoint = "/v1/auth/approle/login";
      body.put("role_id", config.roleId());
      body.put("secret_id", config.secretId());
    }

    HttpRequest.Builder req =
        HttpRequest.newBuilder()
            .uri(URI.create(config.addr() + endpoint))
            .timeout(READ_TIMEOUT)
            .header("Content-Type", "application/json");
    addNamespaceHeader(req);
    HttpResponse<String> resp;
    try {
      resp = sendWith5xxRetry(req.POST(BodyPublishers.ofString(body.toString())).build());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new VaultException("vault " + modeLabel(mode) + " login transport failure", e);
    }
    if (resp.statusCode() / 100 != 2) {
      // NEVER log resp.body() — Vault often echoes auth payload fragments on error.
      throw new VaultException(
          "vault " + modeLabel(mode) + " login failed: status=" + resp.statusCode());
    }
    JsonNode root;
    try {
      root = JSON.readTree(resp.body());
    } catch (IOException e) {
      throw new VaultException("vault " + modeLabel(mode) + " login: response parse failure", e);
    }
    JsonNode auth = root.path("auth");
    String clientToken = auth.path("client_token").asText(null);
    if (clientToken == null || clientToken.isBlank()) {
      throw new VaultException(
          "vault " + modeLabel(mode) + " login: missing client_token in response");
    }
    long leaseDurationSec = auth.path("lease_duration").asLong(0L);
    // Refresh at TTL/2; floor at 30s so a brief TTL doesn't hammer Vault, cap at 1h so a long
    // TTL still gets revalidated periodically (defence-in-depth against revoke-without-expire).
    long refreshSec = Math.max(30L, Math.min(3600L, leaseDurationSec / 2));
    if (leaseDurationSec == 0L) {
      // Vault returns 0 for root tokens (never expire). Use a safe upper bound anyway so we
      // re-validate periodically.
      refreshSec = 3600L;
    }
    this.token = clientToken;
    this.refreshAfter = Instant.now().plusSeconds(refreshSec);
    LOGGER.log(
        Level.INFO,
        "[titan] vault {0} login ok: ttl_seconds={1} refresh_in_seconds={2}",
        new Object[] {modeLabel(mode), leaseDurationSec, refreshSec});
  }

  /**
   * Re-read the kubernetes SA JWT from disk on every login. Projected SA tokens rotate (typically
   * hourly) and a long-running worker MUST observe rotation; caching the JWT here would defeat
   * that.
   *
   * <p>Never log the JWT contents; on read failure include only the path (not the partial contents)
   * in the error.
   */
  @NonNull
  private String readServiceAccountToken() {
    String path = config.resolvedKubernetesSaTokenPath();
    try {
      String jwt = Files.readString(Path.of(path)).trim();
      if (jwt.isEmpty()) {
        throw new VaultException("vault kubernetes login: SA token file is empty at " + path);
      }
      return jwt;
    } catch (IOException e) {
      throw new VaultException("vault kubernetes login: cannot read SA token at " + path, e);
    }
  }

  @NonNull
  private static String modeLabel(@NonNull VaultConfig.AuthMode mode) {
    return switch (mode) {
      case APPROLE -> "approle";
      case TOKEN -> "token";
      case KUBERNETES -> "kubernetes";
    };
  }

  // ── HTTP plumbing ─────────────────────────────────────────────────────────

  @NonNull
  private HttpResponse<String> doKvRead(@NonNull String relPath) {
    String t = ensureToken();
    HttpRequest.Builder req =
        HttpRequest.newBuilder()
            .uri(URI.create(config.addr() + "/v1/" + config.mount() + "/data/" + relPath))
            .timeout(READ_TIMEOUT)
            .header("X-Vault-Token", t)
            .GET();
    addNamespaceHeader(req);
    try {
      return sendWith5xxRetry(req.build());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new VaultException("vault kv read transport failure: path=" + relPath, e);
    }
  }

  @NonNull
  private HttpResponse<String> doKvWrite(@NonNull String relPath, @NonNull String jsonBody) {
    String t = ensureToken();
    HttpRequest.Builder req =
        HttpRequest.newBuilder()
            .uri(URI.create(config.addr() + "/v1/" + config.mount() + "/data/" + relPath))
            .timeout(READ_TIMEOUT)
            .header("X-Vault-Token", t)
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString(jsonBody));
    addNamespaceHeader(req);
    try {
      return sendWith5xxRetry(req.build());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new VaultException("vault kv write transport failure: path=" + safePath(relPath), e);
    }
  }

  @NonNull
  private HttpResponse<String> doKvDelete(@NonNull String relPath, boolean soft) {
    String t = ensureToken();
    HttpRequest.Builder req =
        HttpRequest.newBuilder().timeout(READ_TIMEOUT).header("X-Vault-Token", t);
    if (soft) {
      // Soft delete (KV v2 "delete the latest version"): DELETE /v1/{mount}/data/{path}.
      // The /v1/{mount}/delete/{path} endpoint is for explicitly listing version numbers via the
      // {"versions":[N,...]} body — wrong shape for our "delete the credential" semantics.
      req.uri(URI.create(config.addr() + "/v1/" + config.mount() + "/data/" + relPath)).DELETE();
    } else {
      // Hard delete: DELETE /v1/{mount}/metadata/{path} removes all versions and metadata.
      req.uri(URI.create(config.addr() + "/v1/" + config.mount() + "/metadata/" + relPath))
          .DELETE();
    }
    addNamespaceHeader(req);
    try {
      return sendWith5xxRetry(req.build());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new VaultException("vault kv delete transport failure: path=" + safePath(relPath), e);
    }
  }

  /**
   * Truncate a vault path for inclusion in error messages to avoid leaking tenant info in long
   * paths. Keeps the first 20 characters; appends an ellipsis when truncated.
   */
  @NonNull
  private static String safePath(@NonNull String path) {
    if (path.length() <= 20) {
      return path;
    }
    return path.substring(0, 20) + "...";
  }

  @NonNull
  private HttpResponse<String> sendWith5xxRetry(@NonNull HttpRequest req)
      throws IOException, InterruptedException {
    HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
    if (resp.statusCode() / 100 == 5) {
      LOGGER.log(
          Level.WARNING,
          "[titan] vault 5xx, retrying once: status={0} path={1}",
          new Object[] {resp.statusCode(), req.uri().getPath()});
      resp = http.send(req, BodyHandlers.ofString());
    }
    return resp;
  }

  private void addNamespaceHeader(@NonNull HttpRequest.Builder req) {
    String ns = config.namespace();
    if (ns != null) {
      req.header("X-Vault-Namespace", ns);
    }
  }

  /**
   * Safe diagnostic prefix for a token — first 4 chars, never the whole value. Returns {@code
   * "<none>"} if the token is null/blank. Used in error messages only; never log the full token.
   */
  @NonNull
  private static String tokenPrefix(@Nullable String t) {
    if (t == null || t.isBlank()) {
      return "<none>";
    }
    return t.length() <= 4 ? "****" : t.substring(0, 4) + "...";
  }

  @NonNull
  private String stripMountPrefix(@NonNull String path) {
    String clean = path.startsWith("/") ? path.substring(1) : path;
    String mountPrefix = config.mount() + "/";
    if (clean.startsWith(mountPrefix)) {
      clean = clean.substring(mountPrefix.length());
    }
    // Also strip a leading 'data/' if a caller passed the literal KV v2 wire path.
    if (clean.startsWith("data/")) {
      clean = clean.substring("data/".length());
    }
    return clean;
  }
}
