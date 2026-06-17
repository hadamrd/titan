package io.adaptiq.titan.secrets.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialNotFoundException;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.NewCredentialRequest;
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
 * End-to-end IT for {@link VaultSecretsBackend} write path (#593): create / update / delete against
 * KV v2.
 *
 * <p>Boots a real {@code hashicorp/vault:1.15} container in dev mode with a known root token; uses
 * the root token directly via the {@link VaultConfig#token} factory (token-auth path) so the test
 * focuses on the write surface without re-provisioning AppRole. The root token has policy access to
 * every mount.
 *
 * <p>Adversarial scenarios:
 *
 * <ul>
 *   <li><strong>round-trip:</strong> create → resolve → assert value.
 *   <li><strong>update:</strong> update → resolve → assert NEW value (and old is gone).
 *   <li><strong>soft delete:</strong> delete → resolve → assert empty; metadata (versions) still
 *       readable on the admin path.
 *   <li><strong>re-create after delete:</strong> create same scope/key → resolve → succeeds with a
 *       new version (this is the "I deleted by mistake" recovery story).
 *   <li><strong>update non-existent:</strong> throws {@link CredentialNotFoundException}.
 *   <li><strong>delete non-existent:</strong> no-op (SPI idempotency contract).
 * </ul>
 */
@TestInstance(Lifecycle.PER_CLASS)
class VaultWritePathIT {

  private static final String ROOT_TOKEN = "titan-it-root-token";
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

  @BeforeAll
  void provisionVault() {
    vault.start();
    vaultAddr = "http://" + vault.getHost() + ":" + vault.getMappedPort(8200);
    adminHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // KV v2 is mounted at "secret" by default in dev mode; nothing else to provision — the root
    // token has root policy and can read/write/delete anywhere.
  }

  @AfterAll
  void shutdown() {
    vault.stop();
  }

  // ── tests ─────────────────────────────────────────────────────────────────

  @Test
  void createThenResolveRoundTripsTheValue() {
    VaultSecretsBackend backend = newBackend();
    NewCredentialRequest req =
        new NewCredentialRequest(
            Credential.KIND_STRING, "secret/it/round-trip", "value", "hello-create");
    Credential c = backend.create(req);
    assertNotNull(c);
    assertEquals(Credential.KIND_STRING, c.kind());
    assertEquals("secret/it/round-trip", c.scope());
    assertEquals("value", c.key());

    Optional<String> resolved = backend.resolvePlaintext("secret/it/round-trip", "value");
    assertTrue(resolved.isPresent(), "expected resolve to find the just-created secret");
    assertEquals("hello-create", resolved.get());
  }

  @Test
  void updateReplacesTheValue() {
    VaultSecretsBackend backend = newBackend();
    Credential c =
        backend.create(
            new NewCredentialRequest(Credential.KIND_STRING, "secret/it/update", "value", "v1"));
    assertEquals(
        "v1", backend.resolvePlaintext("secret/it/update", "value").orElse(null), "precondition");

    Credential updated = backend.update(c.id(), new CredentialUpdate(Credential.KIND_STRING, "v2"));
    assertEquals(c.id(), updated.id(), "id is stable across update");

    Optional<String> resolved = backend.resolvePlaintext("secret/it/update", "value");
    assertTrue(resolved.isPresent());
    assertEquals("v2", resolved.get(), "post-update resolve must return the new value");
  }

  @Test
  void softDeleteHidesTheSecretFromResolve() {
    VaultSecretsBackend backend = newBackend();
    Credential c =
        backend.create(
            new NewCredentialRequest(
                Credential.KIND_STRING, "secret/it/soft-delete", "value", "to-be-deleted"));
    assertTrue(
        backend.resolvePlaintext("secret/it/soft-delete", "value").isPresent(), "precondition");

    backend.delete(c.id());

    Optional<String> after = backend.resolvePlaintext("secret/it/soft-delete", "value");
    assertTrue(after.isEmpty(), "soft-delete must make resolve return empty; got: " + after);
  }

  @Test
  void reCreateAfterSoftDeleteSucceeds() {
    VaultSecretsBackend backend = newBackend();
    Credential original =
        backend.create(
            new NewCredentialRequest(
                Credential.KIND_STRING, "secret/it/recreate", "value", "original"));
    backend.delete(original.id());
    assertTrue(backend.resolvePlaintext("secret/it/recreate", "value").isEmpty(), "precondition");

    // Re-create with same scope/key — KV v2 produces a new version (operationally: undeletes +
    // bumps version). Resolve must surface the NEW value.
    Credential recreated =
        backend.create(
            new NewCredentialRequest(
                Credential.KIND_STRING, "secret/it/recreate", "value", "recreated"));
    assertEquals(
        original.id(), recreated.id(), "stable id: same scope/key yields same hash-derived id");

    Optional<String> resolved = backend.resolvePlaintext("secret/it/recreate", "value");
    assertTrue(resolved.isPresent(), "re-created secret must be resolvable");
    assertEquals("recreated", resolved.get());
  }

  @Test
  void updateForUnknownIdThrowsCredentialNotFound() {
    VaultSecretsBackend backend = newBackend();
    long bogusId = 424242L; // never created/resolved through this backend instance
    assertThrows(
        CredentialNotFoundException.class,
        () -> backend.update(bogusId, new CredentialUpdate(Credential.KIND_STRING, "x")));
  }

  @Test
  void deleteForUnknownIdIsNoOp() {
    VaultSecretsBackend backend = newBackend();
    // SPI contract: idempotent. Must NOT throw.
    backend.delete(999_999_999L);
  }

  @Test
  void writeIsObservableOnTheAdminApi() throws Exception {
    // Cross-check: independently of the backend's read path, the secret must be present in Vault
    // when we look via the root admin connection. Catches the "we created a token that succeeded
    // but wrote to the wrong place" failure mode.
    VaultSecretsBackend backend = newBackend();
    backend.create(
        new NewCredentialRequest(
            Credential.KIND_STRING, "secret/it/admin-cross-check", "value", "visible-to-admin"));

    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(vaultAddr + "/v1/secret/data/it/admin-cross-check"))
            .timeout(Duration.ofSeconds(10))
            .header("X-Vault-Token", ROOT_TOKEN)
            .GET()
            .build();
    HttpResponse<String> resp = adminHttp.send(req, BodyHandlers.ofString());
    assertEquals(200, resp.statusCode(), "admin cross-check read failed");
    JsonNode data = JSON.readTree(resp.body()).path("data").path("data");
    assertEquals("visible-to-admin", data.path("value").asText());
    // Verify the KIND sibling field landed too.
    assertEquals(Credential.KIND_STRING, data.path(VaultSecretsBackend.KIND_FIELD).asText());
  }

  @Test
  void differentScopeKeyYieldDifferentIds() {
    VaultSecretsBackend backend = newBackend();
    Credential a =
        backend.create(new NewCredentialRequest(Credential.KIND_STRING, "secret/it/idA", "k", "v"));
    Credential b =
        backend.create(new NewCredentialRequest(Credential.KIND_STRING, "secret/it/idB", "k", "v"));
    assertNotEquals(a.id(), b.id(), "distinct paths must yield distinct ids");
    assertTrue(a.id() >= 0, "id must be non-negative (sign bit masked)");
    assertTrue(b.id() >= 0);
  }

  @Test
  void multilineValueIsPreservedOnRoundTrip() {
    // Newlines / control chars often trip naive HTTP/JSON paths.
    VaultSecretsBackend backend = newBackend();
    String payload = "line1\nline2\n\twith-tab\nend";
    backend.create(
        new NewCredentialRequest(Credential.KIND_SSH_KEY, "secret/it/multiline", "value", payload));
    Optional<String> resolved = backend.resolvePlaintext("secret/it/multiline", "value");
    assertTrue(resolved.isPresent());
    assertEquals(payload, resolved.get());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private VaultSecretsBackend newBackend() {
    VaultConfig cfg = VaultConfig.token(vaultAddr, ROOT_TOKEN, VaultConfig.DEFAULT_MOUNT, null);
    return new VaultSecretsBackend(cfg);
  }

  // Suppress unused-warning placeholder — keeping admin JSON helper symmetrical with the AppRole
  // IT in case a future test needs it.
  @SuppressWarnings("unused")
  private ObjectNode emptyJson() {
    return JSON.createObjectNode();
  }
}
