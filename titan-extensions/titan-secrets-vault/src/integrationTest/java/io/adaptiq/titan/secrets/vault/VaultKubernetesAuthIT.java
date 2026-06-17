package io.adaptiq.titan.secrets.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end IT for {@link VaultSecretsBackend} with kubernetes service-account auth (#593,
 * 3rd-mode follow-up).
 *
 * <p>Mirrors {@link VaultAppRoleResolveIT} / {@link VaultTokenAuthIT} but exercises the {@code
 * KUBERNETES} auth mode: the backend reads a JWT from a file path on disk, POSTs it to {@code
 * /v1/auth/kubernetes/login} with a configured Vault {@code role}, and uses the returned client
 * token to do KV reads.
 *
 * <p>Adversarial scenarios:
 *
 * <ul>
 *   <li><strong>missing SA-token file</strong> — config construction MUST throw with a clear
 *       pointer at the path the operator forgot.
 *   <li><strong>both modes configured</strong> — {@link VaultConfig} MUST throw if the operator
 *       supplies both kubernetes (vaultRole) AND AppRole. Discriminated mode (CONSTITUTION).
 *   <li><strong>empty SA-token file</strong> — file exists but is empty: must surface a clear error
 *       rather than POSTing an empty JWT to Vault.
 *   <li><strong>happy path</strong> — {@link Disabled} because Vault's {@code auth/kubernetes}
 *       endpoint validates the JWT against the kubernetes TokenReview API; reproducing that
 *       end-to-end in a self-contained testcontainer requires either (a) a real kind/k3s cluster or
 *       (b) a mock kubernetes API server. Tracking follow-up: ship in dev-rig as a smoke test, not
 *       unit IT.
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
class VaultKubernetesAuthIT {

  private static final String ROOT_TOKEN = "titan-it-root-token-k8smode";
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

  @TempDir Path tempDir;

  @BeforeAll
  void provisionVault() throws Exception {
    vault.start();
    vaultAddr = "http://" + vault.getHost() + ":" + vault.getMappedPort(8200);
    adminHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    // 1. Enable kubernetes auth backend (idempotent — dev mode doesn't preconfigure it).
    ObjectNode enable = JSON.createObjectNode();
    enable.put("type", "kubernetes");
    rootPost("/v1/sys/auth/kubernetes", enable);

    // 2. Write a policy that allows reading KV v2.
    String policyHcl =
        "path \"secret/data/*\" { capabilities = [\"read\", \"list\"] }\n"
            + "path \"secret/metadata/*\" { capabilities = [\"read\", \"list\"] }\n";
    ObjectNode policy = JSON.createObjectNode();
    policy.put("policy", policyHcl);
    rootPut("/v1/sys/policies/acl/titan-kv-read-k8smode", policy);

    // 3. Seed a known secret (read by the happy-path test once unblocked).
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
  void configWithMissingSaTokenFileThrows() {
    // Pointing at a path that doesn't exist must fail at construction (clear startup error)
    // rather than at the first resolve (cryptic build failure mid-pipeline).
    Path missing = tempDir.resolve("does-not-exist");
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                VaultConfig.kubernetes(
                    vaultAddr,
                    "titan-k8s-role",
                    missing.toString(),
                    VaultConfig.DEFAULT_MOUNT,
                    null));
    String msg = ex.getMessage();
    assertNotNull(msg);
    assertTrue(
        msg.contains(missing.toString()),
        "error must include the missing path so operators can fix it: " + msg);
    assertTrue(msg.toLowerCase().contains("kubernetes"), "error must name the auth mode: " + msg);
  }

  @Test
  void configWithBothKubernetesAndApproleIsRejected() throws Exception {
    // Discriminated mode (CONSTITUTION): mixing auth bundles must throw at construction.
    Path saToken = tempDir.resolve("sa-token-mix");
    Files.writeString(saToken, "synthetic-jwt-token-value");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new VaultConfig(
                    vaultAddr,
                    "some-role",
                    "some-secret",
                    null,
                    "titan-k8s-role",
                    saToken.toString(),
                    "secret",
                    null));
    assertTrue(
        ex.getMessage().contains("mutually exclusive"),
        "error must explain the conflict: " + ex.getMessage());
  }

  @Test
  void configWithBothKubernetesAndTokenIsRejected() throws Exception {
    Path saToken = tempDir.resolve("sa-token-mix-tok");
    Files.writeString(saToken, "synthetic-jwt-token-value");

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new VaultConfig(
                    vaultAddr,
                    null,
                    null,
                    "s.some-direct-token",
                    "titan-k8s-role",
                    saToken.toString(),
                    "secret",
                    null));
    assertTrue(
        ex.getMessage().contains("mutually exclusive"),
        "error must explain the conflict: " + ex.getMessage());
  }

  @Test
  void emptySaTokenFileSurfacesClearLoginError() throws Exception {
    // File exists (passes the construction-time readability check) but is empty — must NOT POST
    // an empty JWT to Vault. The pre-POST check inside readServiceAccountToken() surfaces a
    // clear error without ever hitting the wire, so no Vault admin setup is needed.
    Path saToken = tempDir.resolve("sa-token-empty");
    Files.writeString(saToken, "");

    VaultConfig cfg =
        VaultConfig.kubernetes(
            vaultAddr, "titan-k8s-role-empty", saToken.toString(), VaultConfig.DEFAULT_MOUNT, null);
    assertEquals(VaultConfig.AuthMode.KUBERNETES, cfg.authMode());

    VaultSecretsBackend backend = new VaultSecretsBackend(cfg);
    VaultException ex =
        assertThrows(
            VaultException.class,
            () -> backend.resolvePlaintext("secret/test/integration", "value"));
    String msg = ex.getMessage();
    assertNotNull(msg);
    assertTrue(msg.toLowerCase().contains("empty"), "must explain the empty token: " + msg);
    // Hygiene: must not echo the (empty) JWT — trivially true here, but the read path must not
    // log token contents in any case.
    assertFalse(msg.contains("Bearer "), "must not embed auth headers in errors: " + msg);
  }

  @Test
  void authModeAdvertisesKubernetes() throws Exception {
    Path saToken = tempDir.resolve("sa-token-advertise");
    Files.writeString(saToken, "synthetic-jwt-token-value");
    VaultConfig cfg =
        VaultConfig.kubernetes(
            vaultAddr, "titan-k8s-role", saToken.toString(), VaultConfig.DEFAULT_MOUNT, null);
    assertEquals(VaultConfig.AuthMode.KUBERNETES, cfg.authMode());
    String s = cfg.toString();
    assertTrue(s.contains("authMode=KUBERNETES"), "toString must advertise mode: " + s);
    // vaultRole is a username-analog and is NOT redacted; the SA-token PATH is also fine to log
    // (it's a filesystem location, not a secret).
    assertTrue(s.contains("titan-k8s-role"), "vaultRole is not a secret, may appear: " + s);
  }

  @Test
  @Disabled(
      "Vault's auth/kubernetes endpoint validates the JWT against the kubernetes TokenReview"
          + " API. A self-contained reproduction needs either a real cluster or a mock"
          + " kubernetes API server. Tracked as follow-up — exercise in the k3s rig smoke"
          + " test instead.")
  void resolveReturnsTheValueViaKubernetesAuth() throws Exception {
    Path saToken = tempDir.resolve("sa-token-happy");
    Files.writeString(saToken, "synthetic-jwt-token-value");
    configureVaultKubernetesAuth("titan-k8s-role");

    VaultConfig cfg =
        VaultConfig.kubernetes(
            vaultAddr, "titan-k8s-role", saToken.toString(), VaultConfig.DEFAULT_MOUNT, null);
    VaultSecretsBackend backend = new VaultSecretsBackend(cfg);

    String value = backend.resolvePlaintext("secret/test/integration", "value").orElseThrow();
    assertEquals("hello", value);
  }

  // ── admin helpers ─────────────────────────────────────────────────────────

  /**
   * Configure Vault's kubernetes auth backend to point at a (placeholder) k8s API. In the
   * happy-path test this would be a real mock; for the negative tests we just need the role to
   * exist so that the Vault POST gets past role-lookup before the JWT check fails.
   */
  private void configureVaultKubernetesAuth(String roleName) throws Exception {
    // Configure the auth backend — kubernetes_host is required, value is irrelevant for the
    // negative tests (we never reach the actual TokenReview call).
    ObjectNode authCfg = JSON.createObjectNode();
    authCfg.put("kubernetes_host", "https://kubernetes.default.svc:443");
    authCfg.put("disable_iss_validation", true);
    authCfg.put("disable_local_ca_jwt", true);
    rootPost("/v1/auth/kubernetes/config", authCfg);

    ObjectNode role = JSON.createObjectNode();
    role.putArray("bound_service_account_names").add("titan-worker");
    role.putArray("bound_service_account_namespaces").add("titan");
    role.put("token_ttl", "60s");
    role.put("token_max_ttl", "120s");
    role.put("token_policies", "titan-kv-read-k8smode");
    rootPost("/v1/auth/kubernetes/role/" + roleName, role);
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
}
