package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialResolver;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.flow.model.CredentialBinding;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link CredentialResolver} — the controller side of design/39 / design/32 §12 D6. The
 * resolver is exercised against a real (H2-backed) credentials store seeded via {@code
 * CredentialsService.create(NewCredentialRequest)}.
 *
 * <p>Plaintext shapes per {@link CredentialResolver}'s contract: STRING is the secret itself;
 * USERNAME_PASSWORD is JSON {@code {username, password}}; FILE is base64 of bytes; SSH_KEY is JSON
 * {@code {privateKey, passphrase, username}}.
 */
@QuarkusTest
class CredentialResolverTest {

  @Inject CredentialsService credentialsService;

  /** Disambiguate keys across tests so the application-scoped H2 store stays usable. */
  private static final AtomicLong KEY_SEQ = new AtomicLong();

  private CredentialResolver resolver() {
    return new CredentialResolver(credentialsService);
  }

  private String createString(String label, String plaintext) {
    String key = label + "-" + KEY_SEQ.incrementAndGet() + "-" + System.nanoTime();
    credentialsService.create(
        new NewCredentialRequest(Credential.KIND_STRING, "default", key, plaintext));
    return key;
  }

  private String createUsernamePassword(String label, String user, String password) {
    String key = label + "-" + KEY_SEQ.incrementAndGet() + "-" + System.nanoTime();
    String json = "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}";
    credentialsService.create(
        new NewCredentialRequest(Credential.KIND_USERNAME_PASSWORD, "default", key, json));
    return key;
  }

  private String createFile(String label, byte[] content) {
    String key = label + "-" + KEY_SEQ.incrementAndGet() + "-" + System.nanoTime();
    String base64 = Base64.getEncoder().encodeToString(content);
    credentialsService.create(
        new NewCredentialRequest(Credential.KIND_FILE, "default", key, base64));
    return key;
  }

  private String createSshKey(String label, String privateKey, String passphrase, String username) {
    String key = label + "-" + KEY_SEQ.incrementAndGet() + "-" + System.nanoTime();
    StringBuilder sb = new StringBuilder("{");
    sb.append("\"privateKey\":\"").append(privateKey.replace("\n", "\\n")).append("\"");
    if (passphrase != null) {
      sb.append(",\"passphrase\":\"").append(passphrase).append("\"");
    }
    if (username != null) {
      sb.append(",\"username\":\"").append(username).append("\"");
    }
    sb.append("}");
    credentialsService.create(
        new NewCredentialRequest(Credential.KIND_SSH_KEY, "default", key, sb.toString()));
    return key;
  }

  private static CredentialBinding binding(String id, String type, Map<String, String> bindings) {
    CredentialBinding b = new CredentialBinding();
    b.setId(id);
    b.setType(type);
    b.getBindings().putAll(bindings);
    return b;
  }

  // ── usernamePassword ──────────────────────────────────────────────────────

  @Test
  void resolvesAUsernamePasswordCredentialIntoTwoEnvVars() {
    String id = createUsernamePassword("registry", "svc-deploy", "s3cr3t-pw");

    CredentialsPort.Resolved resolved =
        resolver()
            .resolve(
                List.of(
                    binding(
                        id,
                        CredentialBinding.TYPE_USERNAME_PASSWORD,
                        Map.of("usernameVariable", "REG_USER", "passwordVariable", "REG_PASS"))),
                List.of());

    assertEquals("svc-deploy", resolved.env().get("REG_USER"));
    assertEquals("s3cr3t-pw", resolved.env().get("REG_PASS"));
    // The password is a secret and must be masked; the username is not.
    assertTrue(resolved.maskValues().contains("s3cr3t-pw"));
    assertFalse(resolved.maskValues().contains("svc-deploy"));
    assertTrue(resolved.files().isEmpty());
  }

  // ── string ────────────────────────────────────────────────────────────────

  @Test
  void resolvesASecretTextCredentialIntoOneEnvVar() {
    String id = createString("deploy-token", "tok-abc-123");

    CredentialsPort.Resolved resolved =
        resolver()
            .resolve(
                List.of(
                    binding(id, CredentialBinding.TYPE_STRING, Map.of("variable", "DEPLOY_TOKEN"))),
                List.of());

    assertEquals("tok-abc-123", resolved.env().get("DEPLOY_TOKEN"));
    assertTrue(resolved.maskValues().contains("tok-abc-123"));
  }

  // ── file ──────────────────────────────────────────────────────────────────

  @Test
  void resolvesASecretFileCredentialIntoASecretFile() {
    byte[] content = "kubeconfig-content".getBytes(StandardCharsets.UTF_8);
    String id = createFile("kubeconfig", content);

    CredentialsPort.Resolved resolved =
        resolver()
            .resolve(
                List.of(binding(id, CredentialBinding.TYPE_FILE, Map.of("variable", "KUBECONFIG"))),
                List.of());

    assertEquals(1, resolved.files().size());
    CredentialsPort.SecretFile file = resolved.files().get(0);
    assertEquals("KUBECONFIG", file.variable());
    assertEquals(
        "kubeconfig-content",
        new String(Base64.getDecoder().decode(file.contentBase64()), StandardCharsets.UTF_8));
    // The file content is printable text — masked best-effort so an echo of it does not leak.
    assertTrue(resolved.maskValues().contains("kubeconfig-content"));
  }

  // ── sshKey ────────────────────────────────────────────────────────────────

  @Test
  void resolvesAnSshKeyCredentialIntoAKeyFileAndUsername() {
    String id = createSshKey("prod-ssh", "PRIVATE-KEY-MATERIAL", null, "git");

    CredentialsPort.Resolved resolved =
        resolver()
            .resolve(
                List.of(
                    binding(
                        id,
                        CredentialBinding.TYPE_SSH_KEY,
                        Map.of("keyFileVariable", "KEYFILE", "usernameVariable", "SSH_USER"))),
                List.of());

    assertEquals(1, resolved.files().size());
    assertEquals("KEYFILE", resolved.files().get(0).variable());
    assertEquals("git", resolved.env().get("SSH_USER"));
  }

  // ── error paths (design/39 §5) ────────────────────────────────────────────

  @Test
  void aMissingCredentialIdFailsAtResolution() {
    String missing = "nonexistent-" + KEY_SEQ.incrementAndGet() + "-" + System.nanoTime();
    CredentialResolver.CredentialResolutionException e =
        assertThrows(
            CredentialResolver.CredentialResolutionException.class,
            () ->
                resolver()
                    .resolve(
                        List.of(
                            binding(
                                missing, CredentialBinding.TYPE_STRING, Map.of("variable", "V"))),
                        List.of()));
    assertTrue(e.getMessage().contains(missing), e.getMessage());
    assertTrue(e.getMessage().contains("not found"), e.getMessage());
  }

  @Test
  void aWrongBindingTypeForTheStoredCredentialFails() {
    // A usernamePassword binding against a stored secret-text credential.
    String id = createString("actually-a-string", "x");

    CredentialResolver.CredentialResolutionException e =
        assertThrows(
            CredentialResolver.CredentialResolutionException.class,
            () ->
                resolver()
                    .resolve(
                        List.of(
                            binding(
                                id,
                                CredentialBinding.TYPE_USERNAME_PASSWORD,
                                Map.of("usernameVariable", "U", "passwordVariable", "P"))),
                        List.of()));
    assertTrue(e.getMessage().contains("username/password"), e.getMessage());
  }

  @Test
  void aMissingBindingVariableFails() {
    String id = createString("tok", "x");

    assertThrows(
        CredentialResolver.CredentialResolutionException.class,
        () ->
            resolver()
                .resolve(List.of(binding(id, CredentialBinding.TYPE_STRING, Map.of())), List.of()));
  }

  // ── empty input ───────────────────────────────────────────────────────────

  @Test
  void resolvingAnEmptyBindingListIsAnEmptyResult() {
    CredentialsPort.Resolved resolved = resolver().resolve(List.of(), List.of());
    assertTrue(resolved.isEmpty());
    assertSame(CredentialsPort.EMPTY, resolved);
  }

  // ── declaration-order override ────────────────────────────────────────────

  @Test
  void aLaterBindingOverridesAnEarlierEnvVarOfTheSameName() {
    String first = createString("first", "v1");
    String second = createString("second", "v2");

    CredentialsPort.Resolved resolved =
        resolver()
            .resolve(
                List.of(
                    binding(first, CredentialBinding.TYPE_STRING, Map.of("variable", "SHARED")),
                    binding(second, CredentialBinding.TYPE_STRING, Map.of("variable", "SHARED"))),
                List.of());

    // The step's later binding wins (design/39 §2 — a step entry beats a flattened stage one).
    assertEquals("v2", resolved.env().get("SHARED"));
  }
}
