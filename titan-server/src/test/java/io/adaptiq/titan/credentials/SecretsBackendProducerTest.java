package io.adaptiq.titan.credentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.boot.SecretsBackendProducer;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Adversarial unit tests for {@link SecretsBackendProducer#select} — the backend-selection seam
 * that routes {@code TITAN_SECRETS_BACKEND} to a {@link SecretsBackend} (issue #1227 adds {@code
 * infisical} as a selectable discriminator alongside {@code db-envelope}/{@code vault}).
 *
 * <p>Plain JUnit (no Quarkus): {@code select(String, List)} was extracted precisely so selection is
 * testable without {@code System.getenv} or a real {@link java.util.ServiceLoader} classpath. The
 * producer is constructed with null collaborators because the default-backend path only reads
 * {@link DbEnvelopeBackend#NAME}, never the stores/keyProvider.
 *
 * <p>Hunts the sad path: an unknown / blank discriminator and an unconfigured ("missing token")
 * backend must fail <em>closed</em> with a structured, non-leaking outcome — never a crash that
 * could carry a secret.
 */
class SecretsBackendProducerTest {

  private final SecretsBackendProducer producer = new SecretsBackendProducer(null, null);

  @Test
  void nullDiscriminatorSelectsDefaultDbEnvelopeBackend() {
    SecretsBackend chosen = producer.select(null, List.of());
    assertEquals(DbEnvelopeBackend.NAME, chosen.name());
  }

  @Test
  void blankDiscriminatorSelectsDefaultDbEnvelopeBackend() {
    SecretsBackend chosen = producer.select("   ", List.of());
    assertEquals(
        DbEnvelopeBackend.NAME,
        chosen.name(),
        "a blank TITAN_SECRETS_BACKEND must fall back to the default, not crash");
  }

  @Test
  void namedDiscriminatorSelectsTheMatchingDiscoveredBackend() {
    FakeBackend infisical = new FakeBackend("infisical", Optional.of("resolved"));
    SecretsBackend chosen = producer.select("infisical", List.of(infisical));
    assertSame(infisical, chosen, "the backend whose name() matches must be bound");
  }

  @Test
  void unknownDiscriminatorThrowsAndDoesNotLeakASecret() {
    FakeBackend infisical = new FakeBackend("infisical", Optional.of("super-secret-value"));
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> producer.select("does-not-exist", List.of(infisical)));
    // Fail loud, and helpfully: name the offending discriminator + the available names.
    assertTrue(ex.getMessage().contains("does-not-exist"), ex.getMessage());
    assertTrue(ex.getMessage().contains(DbEnvelopeBackend.NAME), ex.getMessage());
    assertTrue(ex.getMessage().contains("infisical"), ex.getMessage());
    // Never the secret material, even though a discovered backend holds one.
    assertFalse(
        ex.getMessage().contains("super-secret-value"),
        "the selection error must not echo any backend's secret material");
  }

  @Test
  void unconfiguredBackendResolvesEmptyNotACrash() {
    // "missing token" shape: the backend is selected fine, but holds no live connection, so
    // resolvePlaintext returns empty — the CredentialResolver then fails the binding closed with
    // an id-only message. The key property: selection + resolve do not crash and carry no secret.
    FakeBackend unconfigured = new FakeBackend("infisical", Optional.empty());
    SecretsBackend chosen = producer.select("infisical", List.of(unconfigured));
    assertEquals(Optional.empty(), chosen.resolvePlaintext("e2e-secrets", "token"));
  }

  /**
   * Minimal in-memory {@link SecretsBackend} — only {@link #name()} / {@link #resolvePlaintext}.
   */
  private static final class FakeBackend implements SecretsBackend {
    private final String name;
    private final Optional<String> resolved;

    FakeBackend(@NonNull String name, @NonNull Optional<String> resolved) {
      this.name = name;
      this.resolved = resolved;
    }

    @Override
    @NonNull
    public String name() {
      return name;
    }

    @Override
    @NonNull
    public Optional<Credential> findById(long id) {
      return Optional.empty();
    }

    @Override
    @NonNull
    public Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key) {
      return Optional.empty();
    }

    @Override
    @NonNull
    public List<Credential> listAll() {
      return List.of();
    }

    @Override
    @NonNull
    public List<Credential> listByScope(@NonNull String scope) {
      return List.of();
    }

    @Override
    @NonNull
    public Credential create(@NonNull NewCredentialRequest request) {
      throw new UnsupportedOperationException("read-only fake");
    }

    @Override
    @NonNull
    public Credential update(long id, @NonNull CredentialUpdate update) {
      throw new UnsupportedOperationException("read-only fake");
    }

    @Override
    public void delete(long id) {
      // no-op
    }

    @Override
    @NonNull
    public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
      return resolved;
    }

    @Override
    public int rotateKek() {
      return 0;
    }
  }
}
