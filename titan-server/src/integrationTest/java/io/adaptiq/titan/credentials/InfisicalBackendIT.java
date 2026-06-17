package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.CredentialsPort;
import io.adaptiq.titan.flow.crypto.SecretProvider;
import io.adaptiq.titan.flow.model.CredentialBinding;
import io.adaptiq.titan.keyprovider.infisical.InfisicalSecretsBackend;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the Infisical secrets backend (issue #1227), crossing the module boundary
 * from {@code titan-server}'s credential-resolution path into {@code titan-keyprovider-infisical}.
 *
 * <p>It drives the <em>real</em> {@link CredentialResolver} → {@link CredentialsServiceImpl} →
 * {@link InfisicalSecretsBackend} chain, with the Infisical HTTP boundary replaced by a fake {@link
 * SecretProvider} (the typed external seam). This proves the acceptance criterion: a pipeline
 * {@code credentials:} binding resolves a secret that lives in Infisical, selected by the {@code
 * infisical} discriminator — without standing up a live Infisical (the {@code InfisicalClient} HTTP
 * path itself is covered by {@code InfisicalSecretProviderTest}).
 *
 * <p>Plain JUnit (no Quarkus boot, no DB): the unit under test is the resolution wiring, not the
 * HTTP layer, so a heavyweight {@code @QuarkusIntegrationTest} would only add flake.
 */
class InfisicalBackendIT {

  private static final String SCOPE = "e2e-secrets";
  private static final String KEY = "token";
  private static final String SECRET_VALUE = "super-secret-from-infisical";

  /** A deterministic in-memory {@link SecretProvider} — the Infisical stand-in. */
  private static final class FakeSecretProvider implements SecretProvider {
    private final Map<String, String> secrets;

    FakeSecretProvider(@NonNull Map<String, String> secrets) {
      this.secrets = secrets;
    }

    @Override
    @Nullable
    public String secret(@NonNull String name) {
      return secrets.get(name);
    }

    @Override
    @NonNull
    public String describe() {
      return "fake-infisical";
    }
  }

  private static CredentialResolver resolverBacking(@NonNull SecretProvider provider) {
    InfisicalSecretsBackend backend = new InfisicalSecretsBackend(provider);
    return new CredentialResolver(new CredentialsServiceImpl(backend));
  }

  private static CredentialBinding stringBinding(@NonNull String id, @NonNull String envVar) {
    CredentialBinding b = new CredentialBinding();
    b.setId(id);
    b.setType(CredentialBinding.TYPE_STRING);
    b.setBindings(Map.of("variable", envVar));
    return b;
  }

  @Test
  void resolvesAStringCredentialSourcedFromInfisicalIntoEnvAndMasksIt() {
    CredentialResolver resolver =
        resolverBacking(new FakeSecretProvider(Map.of(KEY, SECRET_VALUE)));

    CredentialsPort.Resolved resolved =
        resolver.resolve(List.of(stringBinding(SCOPE + "/" + KEY, "SECRET_TOKEN")), List.of());

    // The Infisical-sourced value is bound into the step env…
    assertEquals(SECRET_VALUE, resolved.env().get("SECRET_TOKEN"));
    // …and registered for log-masking so it cannot leak into the worker's step log.
    assertTrue(
        resolved.maskValues().contains(SECRET_VALUE),
        "the resolved Infisical secret must be a mask value");
  }

  @Test
  void confirmsTheBackendDiscriminatorIsInfisical() {
    assertEquals("infisical", new InfisicalSecretsBackend(new FakeSecretProvider(Map.of())).name());
  }

  @Test
  void missingSecretFailsClosedWithAnIdOnlyNonLeakingError() {
    // Empty provider == the "secret absent / Infisical not configured (missing token)" shape:
    // resolvePlaintext returns empty and the binding fails closed — never a silent unbound run.
    CredentialResolver resolver = resolverBacking(new FakeSecretProvider(Map.of()));

    CredentialsPort.CredentialResolutionException ex =
        assertThrows(
            CredentialsPort.CredentialResolutionException.class,
            () ->
                resolver.resolve(
                    List.of(stringBinding(SCOPE + "/" + KEY, "SECRET_TOKEN")), List.of()));

    // The error names the credential id (operator can act) but carries no secret material.
    assertTrue(ex.getMessage().contains(SCOPE + "/" + KEY), ex.getMessage());
    assertFalse(
        ex.getMessage().contains(SECRET_VALUE),
        "a fail-closed error must never echo the secret value");
  }

  @Test
  void blankInfisicalSecretIsTreatedAsAbsentAndFailsClosed() {
    // Adversarial: Infisical returns an empty/blank value (a misconfigured secret). It must be
    // treated as absent, not bound as an empty env var that a step might silently accept.
    CredentialResolver resolver = resolverBacking(new FakeSecretProvider(Map.of(KEY, "   ")));

    assertThrows(
        CredentialsPort.CredentialResolutionException.class,
        () ->
            resolver.resolve(List.of(stringBinding(SCOPE + "/" + KEY, "SECRET_TOKEN")), List.of()));
  }
}
