package io.adaptiq.titan.secrets.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end IT for {@link VaultSecretsBackend} AppRole + KV v2 read path.
 *
 * <p>Boots a real {@code hashicorp/vault:1.15} container in dev mode with a known root token,
 * configures AppRole via the management API (using the root token over plain HTTP), then exercises
 * the backend via the AppRole credentials only — the backend never sees the root token. This is the
 * load-bearing assertion of #549.
 *
 * <p>Three scenarios cover the adversarial surface:
 *
 * <ul>
 *   <li>happy path: known secret returns expected value.
 *   <li>missing path: returns {@link Optional#empty()}, NOT a thrown error (would otherwise blow up
 *       every "credential not set yet" lookup in the UI).
 *   <li>revoked token: the backend's cached token is revoked out of band; the next resolve MUST
 *       re-login and succeed. This is the "token rotated while controller is up" runtime path and
 *       the entire reason {@code VaultHttpClient} retries on 403.
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
class VaultAppRoleResolveIT {

  private static final String ROOT_TOKEN = "titan-it-root-token";
  private static final ObjectMapper JSON = new ObjectMapper();

  // Vault dev image. Pinned to 1.15 — same major as the version Titan documents
  // for prod deployment. hashicorp/vault is the official image post-rebrand.
  @SuppressWarnings("resource")
  private final GenericContainer<?> vault =
      new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.15"))
          .withExposedPorts(8200)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
          .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  private String vaultAddr;
  private HttpClient adminHttp;

  // AppRole credentials (provisioned in @BeforeAll, consumed by the backend).
  private String roleId;
  private String secretId;

  @BeforeAll
  void provisionVault() throws Exception {
    vault.start();
    vaultAddr = "http://" + vault.getHost() + ":" + vault.getMappedPort(8200);
    adminHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // 1. Enable the approle auth method (idempotent — dev mode doesn't preconfigure it).
    ObjectNode enable = JSON.createObjectNode();
    enable.put("type", "approle");
    rootPost("/v1/sys/auth/approle", enable);

    // 2. Write a policy that allows reading our KV v2 mount. The `default` policy does NOT grant
    // read on secret/*; without this, every kv read returns 403 and we'd silently be testing the
    // backend's auth-failure path instead of its happy path.
    String policyHcl =
        "path \"secret/data/*\" { capabilities = [\"read\", \"list\"] }\n"
            + "path \"secret/metadata/*\" { capabilities = [\"read\", \"list\"] }\n";
    ObjectNode policy = JSON.createObjectNode();
    policy.put("policy", policyHcl);
    rootPut("/v1/sys/policies/acl/titan-kv-read", policy);

    // 3. Create a role with a short-ish TTL bound to the policy above.
    ObjectNode role = JSON.createObjectNode();
    role.put("token_ttl", "60s");
    role.put("token_max_ttl", "120s");
    role.put("token_policies", "titan-kv-read");
    rootPost("/v1/auth/approle/role/titan-it", role);

    // 4. Fetch role-id.
    HttpResponse<String> roleIdResp = rootGet("/v1/auth/approle/role/titan-it/role-id");
    assertEquals(200, roleIdResp.statusCode(), "role-id fetch failed: " + roleIdResp.statusCode());
    roleId = JSON.readTree(roleIdResp.body()).path("data").path("role_id").asText();
    assertNotNull(roleId);
    assertTrue(!roleId.isBlank());

    // 5. Generate a secret-id.
    HttpResponse<String> secretIdResp =
        rootPost("/v1/auth/approle/role/titan-it/secret-id", JSON.createObjectNode());
    assertEquals(200, secretIdResp.statusCode());
    secretId = JSON.readTree(secretIdResp.body()).path("data").path("secret_id").asText();
    assertNotNull(secretId);
    assertTrue(!secretId.isBlank());

    // 6. Write a KV v2 secret at secret/data/test/integration { value: "hello" }.
    ObjectNode kvPayload = JSON.createObjectNode();
    ObjectNode kvData = kvPayload.putObject("data");
    kvData.put("value", "hello");
    kvData.put("multiline", "line1\nline2");
    rootPost("/v1/secret/data/test/integration", kvPayload);
  }

  @AfterAll
  void shutdown() {
    vault.stop();
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void resolveReturnsTheValueForAKnownPath() {
    VaultSecretsBackend backend = newBackend();
    Optional<String> result = backend.resolvePlaintext("secret/test/integration", "value");
    assertTrue(result.isPresent(), "expected resolve to return the seeded value");
    assertEquals("hello", result.get());
  }

  @Test
  void resolveAcceptsPathsWithoutTheMountPrefix() {
    // The path discriminator on a `type: vault` credential might or might not include the mount
    // prefix; the backend MUST accept both shapes.
    VaultSecretsBackend backend = newBackend();
    Optional<String> result = backend.resolvePlaintext("test/integration", "value");
    assertTrue(result.isPresent(), "expected resolve to accept mount-less paths");
    assertEquals("hello", result.get());
  }

  @Test
  void resolveReturnsEmptyForMissingPath() {
    // Critical: a 404 must surface as Optional.empty(), NOT a thrown error.
    // Every "credential not set yet" UI lookup hits this path during onboarding.
    VaultSecretsBackend backend = newBackend();
    Optional<String> result = backend.resolvePlaintext("secret/does/not/exist", "x");
    assertTrue(result.isEmpty(), "expected empty for missing path, got: " + result);
  }

  @Test
  void resolveReturnsEmptyForMissingKeyInPresentPath() {
    VaultSecretsBackend backend = newBackend();
    Optional<String> result = backend.resolvePlaintext("secret/test/integration", "no-such-key");
    assertTrue(result.isEmpty(), "expected empty for missing key");
  }

  @Test
  void resolveRecoversAfterTokenIsRevokedOutOfBand() throws Exception {
    // Drive the backend once so it logs in and caches a token. Then revoke that exact token via
    // the root admin connection. The backend has no way to know the token is dead until its next
    // request returns 403 — at which point VaultHttpClient must invalidate the cache, re-login
    // with AppRole, and retry.
    VaultSecretsBackend backend = newBackend();
    assertEquals(
        "hello", backend.resolvePlaintext("secret/test/integration", "value").orElse(null));

    // Revoke ALL tokens issued to the AppRole role's accessor list. The simplest way in dev mode
    // is to revoke by role name via the token-management API: tidy doesn't apply quickly enough.
    // We instead revoke at the accessor level: list, then revoke each.
    revokeAllAppRoleTokens();

    // Backend STILL holds the now-dead token in cache. The next resolve must observe a 403,
    // re-login, and succeed. If VaultHttpClient lacked the 403 fallback this would throw.
    Optional<String> afterRevoke = backend.resolvePlaintext("secret/test/integration", "value");
    assertTrue(afterRevoke.isPresent(), "expected post-revoke resolve to re-login and succeed");
    assertEquals("hello", afterRevoke.get());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private VaultSecretsBackend newBackend() {
    VaultConfig cfg =
        VaultConfig.appRole(vaultAddr, roleId, secretId, VaultConfig.DEFAULT_MOUNT, null);
    return new VaultSecretsBackend(cfg);
  }

  private HttpResponse<String> rootGet(String path) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(vaultAddr + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-Vault-Token", ROOT_TOKEN)
            .GET()
            .build();
    return adminHttp.send(req, BodyHandlers.ofString());
  }

  private HttpResponse<String> rootPut(String path, JsonNode body) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(vaultAddr + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-Vault-Token", ROOT_TOKEN)
            .header("Content-Type", "application/json")
            .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    HttpResponse<String> resp = adminHttp.send(req, BodyHandlers.ofString());
    assertTrue(
        resp.statusCode() / 100 == 2 || resp.statusCode() == 204,
        "vault admin PUT " + path + " failed: status=" + resp.statusCode());
    return resp;
  }

  private HttpResponse<String> rootPost(String path, JsonNode body) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(vaultAddr + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-Vault-Token", ROOT_TOKEN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    HttpResponse<String> resp = adminHttp.send(req, BodyHandlers.ofString());
    assertTrue(
        resp.statusCode() / 100 == 2 || resp.statusCode() == 204,
        "vault admin call " + path + " failed: status=" + resp.statusCode());
    return resp;
  }

  private void revokeAllAppRoleTokens() throws Exception {
    // List token accessors, then for each one look up the token to inspect its policies; revoke
    // only tokens that hold our titan-kv-read policy (i.e. the ones our AppRole login issued).
    // CRITICAL: must NOT revoke the root token's accessor — that would kill the admin connection
    // and the rest of the test (and @AfterAll teardown) would fail.
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(vaultAddr + "/v1/auth/token/accessors?list=true"))
            .timeout(Duration.ofSeconds(10))
            .header("X-Vault-Token", ROOT_TOKEN)
            .method("LIST", HttpRequest.BodyPublishers.noBody())
            .build();
    HttpResponse<String> resp = adminHttp.send(req, BodyHandlers.ofString());
    if (resp.statusCode() == 404) {
      return; // no tokens
    }
    assertTrue(resp.statusCode() / 100 == 2, "accessor list failed: " + resp.statusCode());
    JsonNode keys = JSON.readTree(resp.body()).path("data").path("keys");
    for (JsonNode k : keys) {
      String accessor = k.asText();

      // lookup-accessor to see policies — skip root tokens.
      ObjectNode lookupBody = JSON.createObjectNode();
      lookupBody.put("accessor", accessor);
      HttpRequest lookup =
          HttpRequest.newBuilder()
              .uri(URI.create(vaultAddr + "/v1/auth/token/lookup-accessor"))
              .timeout(Duration.ofSeconds(10))
              .header("X-Vault-Token", ROOT_TOKEN)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(lookupBody.toString()))
              .build();
      HttpResponse<String> lookupResp = adminHttp.send(lookup, BodyHandlers.ofString());
      if (lookupResp.statusCode() / 100 != 2) {
        continue; // token may have been revoked between the LIST and the lookup; ignore.
      }
      JsonNode policies = JSON.readTree(lookupResp.body()).path("data").path("policies");
      boolean isRoot = false;
      for (JsonNode p : policies) {
        if ("root".equals(p.asText())) {
          isRoot = true;
          break;
        }
      }
      if (isRoot) {
        continue;
      }

      ObjectNode revBody = JSON.createObjectNode();
      revBody.put("accessor", accessor);
      HttpRequest rev =
          HttpRequest.newBuilder()
              .uri(URI.create(vaultAddr + "/v1/auth/token/revoke-accessor"))
              .timeout(Duration.ofSeconds(10))
              .header("X-Vault-Token", ROOT_TOKEN)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(revBody.toString()))
              .build();
      HttpResponse<String> revResp = adminHttp.send(rev, BodyHandlers.ofString());
      assertTrue(
          revResp.statusCode() == 204 || revResp.statusCode() / 100 == 2,
          "revoke-accessor failed: " + revResp.statusCode());
    }
  }
}
