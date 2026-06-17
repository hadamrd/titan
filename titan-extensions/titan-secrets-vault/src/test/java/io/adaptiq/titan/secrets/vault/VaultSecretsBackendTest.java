package io.adaptiq.titan.secrets.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.credentials.SecretsBackend;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * Unit-level adversarial tests for {@link VaultSecretsBackend}.
 *
 * <p>Three concerns under test:
 *
 * <ol>
 *   <li><strong>SPI discovery</strong> — the load-bearing assertion of #475: a plain {@link
 *       ServiceLoader} finds this backend.
 *   <li><strong>Unconfigured behaviour</strong> — when {@code VAULT_ADDR} is unset, the no-arg ctor
 *       MUST succeed (so a controller boots even with the backend on classpath but no env); resolve
 *       MUST throw a clear {@link IllegalStateException} pointing the operator at the env vars to
 *       set.
 *   <li><strong>Write methods on an unconfigured backend</strong> — throw {@link
 *       IllegalStateException} naming the env vars to set. (Write happy-path lives in {@code
 *       VaultWritePathIT}.)
 *   <li><strong>Listings</strong> still return empty (metadata-listing follow-up).
 * </ol>
 *
 * <p>The end-to-end happy path (login + KV read against real Vault) lives in {@code
 * VaultAppRoleResolveIT} since it needs a Testcontainer.
 */
class VaultSecretsBackendTest {

  @Test
  void serviceLoaderDiscoversTheVaultBackend() {
    boolean found = false;
    for (SecretsBackend b : ServiceLoader.load(SecretsBackend.class)) {
      if (VaultSecretsBackend.NAME.equals(b.name())) {
        found = true;
        break;
      }
    }
    assertTrue(
        found,
        "ServiceLoader must discover VaultSecretsBackend via"
            + " META-INF/services/io.adaptiq.titan.credentials.SecretsBackend");
  }

  @Test
  void nameMatchesTheEnvVarContract() {
    assertEquals("vault", new VaultSecretsBackend().name());
  }

  @Test
  void resolvePlaintextThrowsWhenUnconfigured() {
    // Default no-arg ctor in a test JVM has no VAULT_ADDR set, so client is null.
    // Resolve must throw a clear error naming the env vars to set — not NPE, not
    // a silent Optional.empty() that would let a build run without its secret.
    VaultSecretsBackend backend = new VaultSecretsBackend();
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class, () -> backend.resolvePlaintext("global", "github-token"));
    assertNotNull(ex.getMessage());
    assertTrue(
        ex.getMessage().contains("VAULT_ADDR"),
        "error message must name the env var the operator forgot to set: " + ex.getMessage());
  }

  @Test
  void writeMethodsOnUnconfiguredBackendThrowIllegalState() {
    // Same defence as resolvePlaintext: an operator who picked TITAN_SECRETS_BACKEND=vault but
    // forgot VAULT_ADDR must see a clear, actionable error — never a silent success or NPE.
    // create / update fail-loud; delete is the documented exception — it stays idempotent (a no-op
    // for an unknown id), matching the SPI contract.
    VaultSecretsBackend backend = new VaultSecretsBackend();
    IllegalStateException createEx =
        assertThrows(
            IllegalStateException.class,
            () ->
                backend.create(
                    new NewCredentialRequest(Credential.KIND_STRING, "global", "k", "v")));
    assertTrue(
        createEx.getMessage().contains("VAULT_ADDR"),
        "create error must mention VAULT_ADDR: " + createEx.getMessage());

    // update needs an indexed id to even attempt the call; with an unknown id it throws
    // CredentialNotFoundException before the unconfigured check — that's the correct precedence.
    assertThrows(
        io.adaptiq.titan.credentials.CredentialNotFoundException.class,
        () -> backend.update(1L, new CredentialUpdate(Credential.KIND_STRING, "v")));

    // delete: idempotent no-op for unknown id, even when the client is null.
    backend.delete(1L);
  }

  @Test
  void readMethodsReturnEmptyRatherThanThrow() {
    // A freshly wired controller with no Vault listing wired yet must render an
    // empty credentials list, not 500.
    VaultSecretsBackend backend = new VaultSecretsBackend();
    assertTrue(backend.findById(1L).isEmpty());
    assertTrue(backend.findByScopeAndKey("global", "k").isEmpty());
    assertTrue(backend.listAll().isEmpty());
    assertTrue(backend.listByScope("global").isEmpty());
  }

  @Test
  void rotateKekIsAnIntentionalNoop() {
    // Vault owns its own key lifecycle. This is FINAL behaviour for Vault.
    assertEquals(0, new VaultSecretsBackend().rotateKek());
  }

  @Test
  void vaultConfigToStringRedactsCredentials() {
    // Heap-dump / log-on-NPE defence: VaultConfig is sometimes stringified by
    // ops tooling; the roleId/secretId must never appear in plaintext there.
    VaultConfig cfg =
        VaultConfig.appRole(
            "http://vault.example:8200", "the-role-id", "the-secret-id", "secret", null);
    String s = cfg.toString();
    assertTrue(s.contains("<redacted>"), "must mark redacted fields: " + s);
    assertTrue(!s.contains("the-role-id"), "roleId must not leak in toString: " + s);
    assertTrue(!s.contains("the-secret-id"), "secretId must not leak in toString: " + s);
  }

  @Test
  void vaultConfigTokenModeRedactsToken() {
    // Same defence as above for direct-token auth mode (#593).
    VaultConfig cfg =
        VaultConfig.token("http://vault.example:8200", "s.SuperSecretToken123", "secret", null);
    String s = cfg.toString();
    assertEquals(VaultConfig.AuthMode.TOKEN, cfg.authMode());
    assertTrue(s.contains("<redacted>"), "must mark vaultToken redacted: " + s);
    assertTrue(!s.contains("s.SuperSecretToken123"), "token must not leak in toString: " + s);
    assertTrue(s.contains("authMode=TOKEN"), "must mention auth mode in toString: " + s);
  }

  @Test
  void vaultConfigAppRoleModeAdvertisesAuthMode() {
    VaultConfig cfg =
        VaultConfig.appRole("http://vault.example:8200", "rid", "sid", "secret", null);
    assertEquals(VaultConfig.AuthMode.APPROLE, cfg.authMode());
    assertTrue(cfg.toString().contains("authMode=APPROLE"));
  }

  @Test
  void vaultConfigRejectsBothApproleAndToken() {
    // Discriminated mode — operator setting both is a config error, NOT a silent
    // "we picked one for you" surprise. Surfaces at construction, before any HTTP.
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new VaultConfig(
                    "http://vault.example:8200", "rid", "sid", "tok", null, null, "secret", null));
    assertTrue(
        ex.getMessage().contains("mutually exclusive"),
        "error must explain the conflict: " + ex.getMessage());
  }

  @Test
  void vaultConfigRejectsNoAuthAtAll() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new VaultConfig(
                    "http://vault.example:8200", null, null, null, null, null, "secret", null));
    assertTrue(
        ex.getMessage().contains("no auth credentials"),
        "error must explain the missing auth: " + ex.getMessage());
  }

  @Test
  void vaultConfigRejectsHalfApproleNoToken() {
    // Only role-id set — half AppRole with no token to fall back to.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VaultConfig(
                "http://vault.example:8200", "rid", null, null, null, null, "secret", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new VaultConfig(
                "http://vault.example:8200", null, "sid", null, null, null, "secret", null));
  }
}
