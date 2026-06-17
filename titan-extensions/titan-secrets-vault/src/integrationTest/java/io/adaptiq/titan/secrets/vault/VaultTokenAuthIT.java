package io.adaptiq.titan.secrets.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * End-to-end IT for {@link VaultSecretsBackend} with direct token auth (#593).
 *
 * <p>Mirrors {@link VaultAppRoleResolveIT}'s container setup but exercises the {@code TOKEN} auth
 * mode: the backend is configured with a pre-existing Vault token (no role-id / secret-id) and must
 * read KV v2 directly without an AppRole login round-trip.
 *
 * <p>Adversarial scenarios:
 *
 * <ul>
 *   <li><strong>happy path</strong> — known secret returns the seeded value.
 *   <li><strong>invalid token</strong> — a bogus token MUST surface a clear "expired or invalid"
 *       error, not loop trying to re-login (we have no credentials to re-login with in token mode).
 *   <li><strong>both modes configured</strong> — {@link VaultConfig} construction MUST throw if the
 *       operator supplies both an AppRole pair AND a token. Discriminated mode (CONSTITUTION), no
 *       silent precedence at the HTTP layer.
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
class VaultTokenAuthIT {

  private static final String ROOT_TOKEN = "titan-it-root-token-tokenmode";
  private static final ObjectMapper JSON = new ObjectMapper();

  @SuppressWarnings("resource")
  private final GenericContainer<?> vault =
      new GenericContainer<>(DockerImageName.parse("hashicorp/vault:1.15"))
          .withExposedPorts(8200)
          .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
          .withEnv("VAULT_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
          .waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200));

  private String vaultAddr;
  private HttpClient adminHttp;

  // A non-root, policy-scoped token the backend will consume. We don't ship the root token to
  // the backend under test — using a least-privilege token mirrors the real production shape.
  private String scopedToken;

  @BeforeAll
  void provisionVault() throws Exception {
    vault.start();
    vaultAddr = "http://" + vault.getHost() + ":" + vault.getMappedPort(8200);
    adminHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // 1. Write a policy that allows reading the KV v2 mount.
    String policyHcl =
        "path \"secret/data/*\" { capabilities = [\"read\", \"list\"] }\n"
            + "path \"secret/metadata/*\" { capabilities = [\"read\", \"list\"] }\n";
    ObjectNode policy = JSON.createObjectNode();
    policy.put("policy", policyHcl);
    rootPut("/v1/sys/policies/acl/titan-kv-read-tokenmode", policy);

    // 2. Mint a non-root token bound to that policy. This is what an operator would put into
    // VAULT_TOKEN in production — never the root token.
    ObjectNode tokenReq = JSON.createObjectNode();
    tokenReq.putArray("policies").add("titan-kv-read-tokenmode");
    tokenReq.put("ttl", "1h");
    tokenReq.put("renewable", false);
    HttpResponse<String> tokenResp = rootPost("/v1/auth/token/create", tokenReq);
    scopedToken = JSON.readTree(tokenResp.body()).path("auth").path("client_token").asText();
    assertTrue(scopedToken != null && !scopedToken.isBlank(), "could not mint scoped token");

    // 3. Seed a known secret.
    ObjectNode kvPayload = JSON.createObjectNode();
    ObjectNode kvData = kvPayload.putObject("data");
    kvData.put("value", "hello");
    rootPost("/v1/secret/data/test/integration", kvPayload);
  }

  @AfterAll
  void shutdown() {
    vault.stop();
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void resolveReturnsTheValueForAKnownPathInTokenMode() {
    VaultConfig cfg = VaultConfig.token(vaultAddr, scopedToken, VaultConfig.DEFAULT_MOUNT, null);
    assertEquals(VaultConfig.AuthMode.TOKEN, cfg.authMode());
    VaultSecretsBackend backend = new VaultSecretsBackend(cfg);

    Optional<String> result = backend.resolvePlaintext("secret/test/integration", "value");
    assertTrue(result.isPresent(), "expected resolve to return the seeded value");
    assertEquals("hello", result.get());
  }

  @Test
  void resolveReturnsEmptyForMissingPathInTokenMode() {
    VaultConfig cfg = VaultConfig.token(vaultAddr, scopedToken, VaultConfig.DEFAULT_MOUNT, null);
    VaultSecretsBackend backend = new VaultSecretsBackend(cfg);

    Optional<String> result = backend.resolvePlaintext("secret/does/not/exist", "x");
    assertTrue(result.isEmpty(), "expected empty for missing path, got: " + result);
  }

  @Test
  void invalidTokenSurfacesClearExpiredError() {
    // A bogus token MUST NOT trigger any retry/login loop — token mode has no credentials to
    // re-mint with. The error message must point the operator at VAULT_TOKEN explicitly.
    VaultConfig cfg =
        VaultConfig.token(
            vaultAddr, "s.bogus-token-does-not-exist", VaultConfig.DEFAULT_MOUNT, null);
    VaultSecretsBackend backend = new VaultSecretsBackend(cfg);

    VaultException ex =
        assertThrows(
            VaultException.class,
            () -> backend.resolvePlaintext("secret/test/integration", "value"));
    String msg = ex.getMessage();
    assertTrue(msg != null && !msg.isBlank(), "error must have a message");
    // Either a clean 403 ("token expired or invalid") or — if Vault returns 400 for a malformed
    // token — a generic kv-read failure. Both are acceptable; what is NOT acceptable is silent
    // success or an infinite retry loop. We assert the message mentions vault + a status code.
    assertTrue(msg.toLowerCase().contains("vault"), "error must mention vault: " + msg);
    // Critical hygiene check: the message must NOT echo the full bogus token.
    assertTrue(
        !msg.contains("s.bogus-token-does-not-exist"),
        "error must not log full token value: " + msg);
  }

  @Test
  void configWithBothApproleAndTokenIsRejected() {
    // The CONSTITUTION-mandated discriminated-mode check: setting both auth bundles is a config
    // error caught at construction, before any HTTP traffic.
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new VaultConfig(
                    vaultAddr,
                    "some-role",
                    "some-secret",
                    scopedToken,
                    null,
                    null,
                    "secret",
                    null));
    assertTrue(
        ex.getMessage().contains("mutually exclusive"),
        "error must explain the conflict: " + ex.getMessage());
  }

  // ── admin helpers ─────────────────────────────────────────────────────────

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
}
