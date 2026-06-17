package io.adaptiq.titan.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.dto.UserDto;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Thin wrapper over the Keycloak admin REST API used by {@code GET /api/v1/admin/users} (backend
 * half of #603).
 *
 * <p>Strategy:
 *
 * <ol>
 *   <li>Resolve {@code kc-base} + {@code realm} from {@code quarkus.oidc.auth-server-url}. The
 *       Quarkus OIDC config already points at {@code {kc-base}/realms/{realm}} — we strip the
 *       trailing {@code /realms/{realm}} to derive {@code kc-base} and capture {@code realm}.
 *   <li>If {@code TITAN_KC_ADMIN_CLIENT_ID} / {@code TITAN_KC_ADMIN_CLIENT_SECRET} are unset (the
 *       current default — the dev-rig {@code titan-server} client is {@code bearerOnly} and has no
 *       service account; see follow-up #603-rig-seed), fall back to a hard-coded STUB user list so
 *       the UI half can be built against a stable shape without blocking on rig changes.
 *   <li>Otherwise: fetch a service-account token via {@code client_credentials}, cache it in memory
 *       for {@code expires_in / 2} seconds, and use it to call:
 *       <ul>
 *         <li>{@code GET {kc-base}/admin/realms/{realm}/users?first=&max=}
 *         <li>{@code GET {kc-base}/admin/realms/{realm}/users/{id}/role-mappings/realm}
 *       </ul>
 * </ol>
 *
 * <p><strong>Security.</strong> The bearer token is held only in-memory and is NEVER logged (only
 * its expiry instant is logged at DEBUG). Service-account users (entries where {@code
 * serviceAccountClientId} is non-null in the Keycloak response) are filtered out — they are
 * infrastructure principals, not humans for the admin UI.
 */
@ApplicationScoped
public class KeycloakAdminClient {

  private static final Logger LOGGER = Logger.getLogger(KeycloakAdminClient.class.getName());

  /** Stable stub used when no admin client credentials are configured (dev rig default). */
  private static final List<UserDto> STUB_USERS =
      List.of(
          new UserDto(
              "dev",
              "dev@titan.local",
              "Dev User",
              List.of(
                  "READ_JOB",
                  "TRIGGER_BUILD",
                  "EDIT_PIPELINE",
                  "READ_AUDIT",
                  "ABORT_BUILD",
                  "OPERATE_WORKER",
                  "REPLAY_BUILD",
                  "MANAGE_CREDENTIALS",
                  "ADMIN")));

  private final Optional<String> authServerUrl;
  private final Optional<String> adminClientId;
  private final Optional<String> adminClientSecret;
  private final HttpClient http;
  private final ObjectMapper mapper = new ObjectMapper();

  // Token cache — written only under the synchronized {@link #cachedToken} accessor.
  private volatile String cachedToken;
  private volatile Instant cachedTokenExpiry = Instant.EPOCH;

  KeycloakAdminClient(
      @ConfigProperty(name = "quarkus.oidc.auth-server-url") Optional<String> authServerUrl,
      @ConfigProperty(name = "titan.kc.admin.client-id") Optional<String> adminClientId,
      @ConfigProperty(name = "titan.kc.admin.client-secret") Optional<String> adminClientSecret) {
    this.authServerUrl = authServerUrl.filter(s -> !s.isBlank());
    this.adminClientId = adminClientId.filter(s -> !s.isBlank());
    this.adminClientSecret = adminClientSecret.filter(s -> !s.isBlank());
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  /**
   * @return whether real Keycloak admin credentials are configured. When {@code false}, callers
   *     should expect {@link #listUsers(int, int)} to serve the stub list.
   */
  public boolean hasRealCredentials() {
    return authServerUrl.isPresent() && adminClientId.isPresent() && adminClientSecret.isPresent();
  }

  /**
   * List users in the configured realm, page {@code [offset, offset+limit)}.
   *
   * <p>Service-account users are filtered out. {@code realmRoles} is populated per-user via the
   * realm role-mappings endpoint.
   */
  public List<UserDto> listUsers(int offset, int limit) {
    if (!hasRealCredentials()) {
      // Apply the page window even to the stub so paging-edge UI behaves identically in dev.
      int from = Math.min(offset, STUB_USERS.size());
      int to = Math.min(offset + limit, STUB_USERS.size());
      return STUB_USERS.subList(from, to);
    }

    try {
      String token = fetchToken();
      String base = kcBase();
      String realm = realm();
      URI usersUri =
          URI.create(
              base
                  + "/admin/realms/"
                  + URLEncoder.encode(realm, StandardCharsets.UTF_8)
                  + "/users?first="
                  + offset
                  + "&max="
                  + limit);
      HttpResponse<String> usersResp =
          http.send(
              HttpRequest.newBuilder(usersUri)
                  .timeout(Duration.ofSeconds(10))
                  .header("Authorization", "Bearer " + token)
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (usersResp.statusCode() / 100 != 2) {
        throw new IllegalStateException("keycloak admin /users returned " + usersResp.statusCode());
      }
      JsonNode arr = mapper.readTree(usersResp.body());
      List<UserDto> out = new ArrayList<>();
      for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) {
        JsonNode u = it.next();
        // Filter service accounts.
        if (u.hasNonNull("serviceAccountClientId")) {
          continue;
        }
        String id = u.path("id").asText("");
        String username = u.path("username").asText("");
        String email = u.path("email").asText(null);
        String first = u.path("firstName").asText("");
        String last = u.path("lastName").asText("");
        String display = (first + " " + last).trim();
        if (display.isEmpty()) {
          display = username;
        }
        List<String> roles = id.isEmpty() ? List.of() : fetchRealmRoles(token, base, realm, id);
        out.add(new UserDto(username, email, display, roles));
      }
      return out;
    } catch (Exception e) {
      // Never leak the bearer or admin secret into log/exception messages.
      LOGGER.log(
          Level.WARNING,
          "[titan-api] keycloak admin user list failed: {0}",
          e.getClass().getSimpleName());
      throw new IllegalStateException("keycloak admin call failed", e);
    }
  }

  // ── internals ─────────────────────────────────────────────────────────────

  private List<String> fetchRealmRoles(String token, String base, String realm, String userId)
      throws Exception {
    URI uri =
        URI.create(
            base
                + "/admin/realms/"
                + URLEncoder.encode(realm, StandardCharsets.UTF_8)
                + "/users/"
                + URLEncoder.encode(userId, StandardCharsets.UTF_8)
                + "/role-mappings/realm");
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      return List.of();
    }
    JsonNode arr = mapper.readTree(resp.body());
    List<String> roles = new ArrayList<>();
    for (Iterator<JsonNode> it = arr.elements(); it.hasNext(); ) {
      String n = it.next().path("name").asText(null);
      if (n != null) {
        roles.add(n);
      }
    }
    return roles;
  }

  /** Cached service-account access token. Refreshes when {@link #cachedTokenExpiry} is past. */
  private synchronized String fetchToken() throws Exception {
    if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
      return cachedToken;
    }
    URI tokenUri = URI.create(authServerUrl.orElseThrow() + "/protocol/openid-connect/token");
    String form =
        "grant_type=client_credentials&client_id="
            + URLEncoder.encode(adminClientId.orElseThrow(), StandardCharsets.UTF_8)
            + "&client_secret="
            + URLEncoder.encode(adminClientSecret.orElseThrow(), StandardCharsets.UTF_8);
    HttpResponse<String> resp =
        http.send(
            HttpRequest.newBuilder(tokenUri)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() / 100 != 2) {
      throw new IllegalStateException("keycloak token endpoint returned " + resp.statusCode());
    }
    JsonNode body = mapper.readTree(resp.body());
    String access = body.path("access_token").asText(null);
    long expiresIn = body.path("expires_in").asLong(60L);
    if (access == null) {
      throw new IllegalStateException("keycloak token response missing access_token");
    }
    cachedToken = access;
    // Cache for half the TTL so we always have headroom before expiry.
    cachedTokenExpiry = Instant.now().plusSeconds(Math.max(10L, expiresIn / 2));
    LOGGER.log(
        Level.FINE, "[titan-api] cached keycloak admin token (expires_in/2={0}s)", expiresIn / 2);
    return cachedToken;
  }

  /**
   * Strip the trailing {@code /realms/{realm}} from {@link #authServerUrl} to yield the Keycloak
   * base URL (e.g. {@code http://kc:8080}).
   */
  private String kcBase() {
    String url = authServerUrl.orElseThrow();
    int idx = url.indexOf("/realms/");
    if (idx < 0) {
      throw new IllegalStateException(
          "quarkus.oidc.auth-server-url does not contain '/realms/' segment");
    }
    return url.substring(0, idx);
  }

  private String realm() {
    String url = authServerUrl.orElseThrow();
    int idx = url.indexOf("/realms/");
    if (idx < 0) {
      throw new IllegalStateException(
          "quarkus.oidc.auth-server-url does not contain '/realms/' segment");
    }
    String tail = url.substring(idx + "/realms/".length());
    int slash = tail.indexOf('/');
    return slash < 0 ? tail : tail.substring(0, slash);
  }
}
