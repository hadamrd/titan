package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialResolver;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.flow.CredentialsPort.SshAgentKey;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CredentialResolver#resolveSshAgent} — the controller side of design/41 §3, resolving
 * against the Titan {@link CredentialsService}. An {@code sshAgent:} id must name an {@link
 * Credential#KIND_SSH_KEY} credential; the resolver produces the private-key text plus an optional
 * passphrase, both masked in the step log.
 */
@QuarkusTest
class SshAgentResolverTest {

  private static final String UNPROTECTED_KEY =
      "-----BEGIN OPENSSH PRIVATE KEY-----\nUNPROTECTED-KEY-MATERIAL\n"
          + "-----END OPENSSH PRIVATE KEY-----";
  private static final String PROTECTED_KEY =
      "-----BEGIN OPENSSH PRIVATE KEY-----\nPROTECTED-KEY-MATERIAL\n"
          + "-----END OPENSSH PRIVATE KEY-----";

  @Inject CredentialsService credentialsService;

  private static final AtomicLong KEY_SEQ = new AtomicLong();

  private CredentialResolver resolver() {
    return new CredentialResolver(credentialsService);
  }

  private String storeSshKey(String label, String key, String passphrase) {
    String credKey = label + "-" + KEY_SEQ.incrementAndGet() + "-" + System.nanoTime();
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"privateKey\":\"").append(key.replace("\n", "\\n")).append("\"");
    if (passphrase != null && !passphrase.isEmpty()) {
      sb.append(",\"passphrase\":\"").append(passphrase).append("\"");
    }
    sb.append(",\"username\":\"git\"}");
    credentialsService.create(
        new NewCredentialRequest(Credential.KIND_SSH_KEY, "default", credKey, sb.toString()));
    return credKey;
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void resolvesAnSshUserPrivateKeyIntoAnSshAgentKey() {
    String id = storeSshKey("prod-deploy-key", UNPROTECTED_KEY, null);

    List<SshAgentKey> keys = resolver().resolveSshAgent(List.of(id));

    assertEquals(1, keys.size());
    assertEquals(UNPROTECTED_KEY, keys.get(0).privateKey().strip());
    assertNull(keys.get(0).passphrase(), "an unprotected key has no passphrase");
  }

  @Test
  void theResolvedPrivateKeyIsAddedToMaskValues() {
    String id = storeSshKey("prod-deploy-key", UNPROTECTED_KEY, null);

    CredentialsPort.Resolved resolved = resolver().resolve(List.of(), List.of(id));

    // The private-key text is printable and must be masked in the step log (design/41 §3).
    assertTrue(
        resolved.maskValues().contains(UNPROTECTED_KEY.strip()),
        "the private key must be a mask value: " + resolved.maskValues());
  }

  @Test
  void resolvesAPassphraseProtectedKeyWithThePassphrasePopulatedAndMasked() {
    String id = storeSshKey("locked-key", PROTECTED_KEY, "s3cr3t-passphrase");

    CredentialsPort.Resolved resolved = resolver().resolve(List.of(), List.of(id));

    assertEquals(1, resolved.sshAgentKeys().size());
    assertEquals("s3cr3t-passphrase", resolved.sshAgentKeys().get(0).passphrase());
    assertTrue(
        resolved.maskValues().contains("s3cr3t-passphrase"),
        "the passphrase must be masked in the step log");
  }

  @Test
  void resolvesMultipleIdsInDeclarationOrder() {
    String idA = storeSshKey("key-a", UNPROTECTED_KEY, null);
    String idB = storeSshKey("key-b", PROTECTED_KEY, "pw");

    List<SshAgentKey> keys = resolver().resolveSshAgent(List.of(idA, idB));

    assertEquals(2, keys.size());
    assertEquals(UNPROTECTED_KEY, keys.get(0).privateKey().strip());
    assertEquals(PROTECTED_KEY, keys.get(1).privateKey().strip());
  }

  // ── error paths (design/41 §3 — fail at dispatch) ─────────────────────────

  @Test
  void aMissingSshAgentIdFailsAtResolution() {
    String missing = "nonexistent-" + KEY_SEQ.incrementAndGet() + "-" + System.nanoTime();
    CredentialResolver.CredentialResolutionException e =
        assertThrows(
            CredentialResolver.CredentialResolutionException.class,
            () -> resolver().resolveSshAgent(List.of(missing)));
    assertTrue(e.getMessage().contains(missing), e.getMessage());
    assertTrue(e.getMessage().contains("not found"), e.getMessage());
  }

  @Test
  void aWrongCredentialTypeForAnSshAgentIdFails() {
    // A STRING credential handed to resolveSshAgent — wrong shape.
    String credKey = "not-an-ssh-key-" + KEY_SEQ.incrementAndGet() + "-" + System.nanoTime();
    credentialsService.create(
        new NewCredentialRequest(Credential.KIND_STRING, "default", credKey, "x"));

    CredentialResolver.CredentialResolutionException e =
        assertThrows(
            CredentialResolver.CredentialResolutionException.class,
            () -> resolver().resolveSshAgent(List.of(credKey)));
    assertTrue(e.getMessage().contains(credKey), e.getMessage());
    assertTrue(e.getMessage().contains("SSH private key"), e.getMessage());
  }

  // ── the Resolved bundle drives the fail-closed TITAN_CREDENTIAL_KEY path ──

  @Test
  void aBundleWithSshAgentKeysIsNotEmpty() {
    String id = storeSshKey("prod-deploy-key", UNPROTECTED_KEY, null);

    CredentialsPort.Resolved resolved = resolver().resolve(List.of(), List.of(id));

    // isEmpty()==false means the orchestrator's fail-closed TITAN_CREDENTIAL_KEY path
    // triggers — a step with sshAgent: and no key configured is failed at dispatch.
    assertFalse(resolved.isEmpty(), "a bundle carrying sshAgentKeys must not be empty");
  }

  @Test
  void anEmptySshAgentListYieldsTheEmptyBundle() {
    CredentialsPort.Resolved resolved = resolver().resolve(List.of(), List.of());
    assertTrue(resolved.isEmpty());
    assertTrue(resolved.sshAgentKeys().isEmpty());
  }
}
