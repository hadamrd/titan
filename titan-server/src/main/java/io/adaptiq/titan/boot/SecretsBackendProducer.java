package io.adaptiq.titan.boot;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.credentials.DbEnvelopeBackend;
import io.adaptiq.titan.credentials.SecretsBackend;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.store.TitanStores;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Selects the active {@link SecretsBackend} for the application lifetime.
 *
 * <p>The default is {@link DbEnvelopeBackend} ({@value DbEnvelopeBackend#NAME}). Operators select a
 * different backend by setting the {@code TITAN_SECRETS_BACKEND} env var to that backend's {@link
 * SecretsBackend#name()}; the matching {@link ServiceLoader}-discovered implementation is bound.
 *
 * <p>Discovery: every implementation that ships a {@code
 * META-INF/services/io.adaptiq.titan.credentials.SecretsBackend} entry is loaded. If no env var is
 * set, the default is used. If an env var is set but no backend matches, startup fails — silently
 * falling back would put a backend the operator did not pick on the live secret path.
 */
@ApplicationScoped
public class SecretsBackendProducer {

  private static final Logger LOGGER = Logger.getLogger(SecretsBackendProducer.class.getName());
  private static final String ENV_VAR = "TITAN_SECRETS_BACKEND";

  private final TitanStores stores;
  private final CredentialKeyProvider keyProvider;

  public SecretsBackendProducer(TitanStores stores, CredentialKeyProvider keyProvider) {
    this.stores = stores;
    this.keyProvider = keyProvider;
  }

  @Produces
  @ApplicationScoped
  public SecretsBackend secretsBackend() {
    List<SecretsBackend> discovered = new ArrayList<>();
    ServiceLoader.load(SecretsBackend.class).forEach(discovered::add);
    return select(System.getenv(ENV_VAR), discovered);
  }

  /**
   * Pure selection logic, split out of {@link #secretsBackend()} so it is unit-testable without
   * touching {@code System.getenv} or the {@link ServiceLoader} classpath.
   *
   * <p>A {@code null} or blank {@code requested} selects the built-in {@link DbEnvelopeBackend}. A
   * non-blank value selects the matching {@link ServiceLoader}-discovered backend, or — if none
   * matches — fails loud with a message that lists the available names (and never any secret),
   * because silently falling back would put a backend the operator did not pick on the live secret
   * path.
   *
   * @param requested the raw {@code TITAN_SECRETS_BACKEND} value (may be {@code null}/blank)
   * @param discovered the {@link ServiceLoader}-discovered backends (may be empty)
   */
  @NonNull
  public SecretsBackend select(
      @Nullable String requested, @NonNull List<SecretsBackend> discovered) {
    String target =
        requested != null && !requested.isBlank() ? requested.strip() : DbEnvelopeBackend.NAME;

    // The default backend is always available — it lives in this jar and we construct it directly
    // so it does not need to be on the ServiceLoader path (which would imply discovery via a
    // public no-arg constructor it does not have).
    if (DbEnvelopeBackend.NAME.equals(target)) {
      LOGGER.log(Level.INFO, "[titan] secrets backend: {0}", DbEnvelopeBackend.NAME);
      return new DbEnvelopeBackend(stores, keyProvider);
    }

    for (SecretsBackend b : discovered) {
      if (target.equals(b.name())) {
        LOGGER.log(Level.INFO, "[titan] secrets backend: {0}", b.name());
        return b;
      }
    }
    throw new IllegalStateException(
        "no SecretsBackend named '"
            + target
            + "' found on the classpath (TITAN_SECRETS_BACKEND). "
            + "Available: ["
            + DbEnvelopeBackend.NAME
            + (discovered.isEmpty()
                ? ""
                : discovered.stream().map(SecretsBackend::name).reduce("", (a, n) -> a + ", " + n))
            + "]");
  }
}
