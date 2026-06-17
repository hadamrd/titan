package io.adaptiq.titan.secrets.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.credentials.DbEnvelopeBackend;
import io.adaptiq.titan.credentials.SecretsBackend;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the {@link SecretsBackend} SPI surface, the load-bearing assertion of #475.
 *
 * <p>Mirrors what {@link io.adaptiq.titan.boot.SecretsBackendProducer} does at controller boot:
 * walks every {@link ServiceLoader}-discovered {@code SecretsBackend} on the classpath and asserts
 * the registry contains exactly the two backends we expect — the in-tree {@code db-envelope}
 * (handled directly by the producer, not via ServiceLoader) and the dropped-in {@code vault}.
 *
 * <p>Why this lives as an IT and not a unit test: the unit-test variant in {@link
 * VaultSecretsBackendTest} verifies "vault is on the classpath" in isolation. This IT additionally
 * asserts the <strong>composition</strong> works — a freshly assembled classpath with both modules
 * present produces exactly the registry the producer expects. If a future extension accidentally
 * shadows the vault entry, or breaks the {@code META-INF/services} resource merge, this test fails
 * loudly.
 */
class VaultBackendSpiDiscoveryIT {

  @Test
  void serviceLoaderDiscoversExactlyTheVaultBackend() {
    // We only assert about ServiceLoader-discovered backends here, NOT about
    // DbEnvelopeBackend (which the producer constructs directly because its
    // constructor needs TitanStores + KeyProvider, so it cannot have the
    // no-arg ctor the ServiceLoader requires). The vault skeleton IS
    // ServiceLoader-discoverable: that is the whole #475 contract.
    List<SecretsBackend> discovered = new ArrayList<>();
    ServiceLoader.load(SecretsBackend.class).forEach(discovered::add);

    Set<String> names = new LinkedHashSet<>();
    for (SecretsBackend b : discovered) {
      names.add(b.name());
    }

    assertTrue(
        names.contains(VaultSecretsBackend.NAME),
        "vault backend must be discovered via ServiceLoader; got: " + names);

    // No other extension module is on this classpath, so the only entry
    // SHOULD be vault. If this ever picks up a stowaway backend it's a
    // dependency leak worth investigating.
    assertEquals(
        Set.of(VaultSecretsBackend.NAME),
        names,
        "unexpected SecretsBackend(s) discovered — classpath leakage? got: " + names);
  }

  @Test
  void registryView_inTreePlusVault_matchesProducerExpectation() {
    // The full registry from the controller's point of view is:
    //   * the in-tree DbEnvelopeBackend (always present, constructed directly)
    //   * every ServiceLoader-discovered backend
    // Compose them the same way SecretsBackendProducer does and assert size=2,
    // names={db-envelope, vault}. This is the case-study assertion of #475.
    Set<String> names = new LinkedHashSet<>();
    names.add(DbEnvelopeBackend.NAME);
    for (SecretsBackend b : ServiceLoader.load(SecretsBackend.class)) {
      names.add(b.name());
    }
    assertEquals(
        Set.of(DbEnvelopeBackend.NAME, VaultSecretsBackend.NAME),
        names,
        "controller's SecretsBackend registry should contain exactly db-envelope + vault");
    assertEquals(2, names.size(), "registry must contain exactly 2 backends");
  }

  @Test
  void vaultBackendIsConstructibleViaNoArgCtor() {
    // ServiceLoader uses the public no-arg ctor. If it ever grows a
    // constructor argument, ServiceLoader silently drops the entry and the
    // discovery test above stays green for the wrong reason. Pin the contract.
    SecretsBackend instance = null;
    for (SecretsBackend b : ServiceLoader.load(SecretsBackend.class)) {
      if (VaultSecretsBackend.NAME.equals(b.name())) {
        instance = b;
        break;
      }
    }
    assertNotNull(instance, "vault backend must be ServiceLoader-instantiable");
    assertTrue(instance instanceof VaultSecretsBackend);
  }
}
