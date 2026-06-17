package io.adaptiq.titan.boot;

import io.adaptiq.titan.credentials.ConfigException;
import io.adaptiq.titan.credentials.DevAutoKeyProvider;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.EnvCredentialKeyProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * CDI producer for the active {@link CredentialKeyProvider} — the credential-sealing key source
 * (design/39 §3.1).
 *
 * <h2>Composition</h2>
 *
 * The producer assembles the provider chain from three sources in priority order:
 *
 * <ol>
 *   <li><strong>{@link DevAutoKeyProvider}</strong> — looked up via {@link Instance} because its
 *       no-arg constructor refuses (throws {@link ConfigException}) outside the {@code dev} profile
 *       (issue #1076 / V1-bar #5). In prod the lookup fails; we log + skip. In dev the bean is
 *       added unconditionally (it stays inert until {@code LOOP_TITAN_ALLOW_DEV_KEK=1}).
 *   <li><strong>{@link ServiceLoader}-discovered providers</strong> (back-compat for any drop-in
 *       provider jar — e.g. {@code titan-keyprovider-infisical}), de-duplicated by class.
 *   <li><strong>{@link EnvCredentialKeyProvider}</strong> as the last-resort fallback, mirroring
 *       the contract of {@link CredentialKeyProvider#active()}.
 * </ol>
 *
 * <h2>Fail-fast at boot in non-dev profiles (#1076 acceptance #2)</h2>
 *
 * After composition, if the profile is NOT {@code dev} AND the chain yields a {@code null} key
 * (i.e. no real KMS provider is wired and {@code TITAN_CREDENTIAL_KEY} is unset), the producer
 * throws {@link ConfigException} from this method. Quarkus aborts boot with the error visible in
 * the startup log — much louder than the prior behaviour, which only failed at the first
 * credential-write attempt via {@code DbEnvelopeBackend.activeKek()}.
 */
@ApplicationScoped
public class CredentialKeyProviderProducer {

  private static final Logger LOGGER =
      Logger.getLogger(CredentialKeyProviderProducer.class.getName());

  private final Instance<DevAutoKeyProvider> devAutoLookup;

  @Inject
  public CredentialKeyProviderProducer(Instance<DevAutoKeyProvider> devAutoLookup) {
    this.devAutoLookup = devAutoLookup;
  }

  @Produces
  @ApplicationScoped
  public CredentialKeyProvider credentialKeyProvider() {
    List<CredentialKeyProvider> chain = new ArrayList<>();
    Set<Class<?>> seen = new HashSet<>();

    // 1. Dev-only provider — lookup may fail with ConfigException in prod (the #1076 gate). That
    //    is exactly the design: prod must not silently mint a host-local KEK. We log+skip rather
    //    than re-throwing here so the chain still gets a chance to discover a real KMS provider;
    //    the fail-fast guard at the bottom of this method covers the no-KEK-at-all case.
    DevAutoKeyProvider devAuto = tryGetDevAuto();
    if (devAuto != null) {
      chain.add(devAuto);
      seen.add(devAuto.getClass());
    }

    // 2. ServiceLoader providers (drop-in KMS / Infisical jars), de-duplicated. We iterate via
    //    the streaming `Provider` API rather than the eager `iterator()` so that a provider whose
    //    constructor refuses (e.g. DevAutoKeyProvider's #1076 prod-profile gate) does not abort
    //    the whole iteration with a ServiceConfigurationError. The dev provider is also
    //    META-INF/services-registered and will be encountered here on a prod boot — we expect it
    //    to throw and skip it.
    for (ServiceLoader.Provider<CredentialKeyProvider> p :
        (Iterable<ServiceLoader.Provider<CredentialKeyProvider>>)
            ServiceLoader.load(CredentialKeyProvider.class).stream()::iterator) {
      try {
        CredentialKeyProvider provider = p.get();
        if (seen.add(provider.getClass())) {
          chain.add(provider);
        }
      } catch (RuntimeException | ServiceConfigurationError e) {
        Throwable cause = e;
        while (cause != null && !(cause instanceof ConfigException) && cause.getCause() != null) {
          cause = cause.getCause();
        }
        LOGGER.log(
            Level.INFO,
            "[titan] credential-key provider {0} refused construction; skipping: {1}",
            new Object[] {p.type().getName(), cause != null ? cause.getMessage() : e.getMessage()});
      }
    }

    // 3. Built-in env fallback — last resort, matches CredentialKeyProvider.active() contract.
    chain.add(new EnvCredentialKeyProvider());

    LOGGER.log(
        Level.INFO,
        "[titan] credential-key chain: {0}",
        chain.stream().map(CredentialKeyProvider::describe).toList());

    ProducerChain composed = new ProducerChain(List.copyOf(chain));

    // Fail-fast (#1076 acceptance #2): in non-dev profile, refuse to boot if the chain has no
    // material to seal credentials with. This used to surface as an HTTP 500 on the first
    // POST /api/v1/credentials, hours after boot; now it aborts startup with a runbook pointer.
    if (!DevAutoKeyProvider.isDevProfile() && composed.credentialKey() == null) {
      throw new ConfigException(
          "No credential KEK provider is configured and titan.profile != dev. titan-server"
              + " refuses to boot in fail-closed mode rather than start an instance that cannot"
              + " seal user-submitted secrets. Configure a KMS-backed provider"
              + " (e.g. titan-keyprovider-infisical) or set TITAN_CREDENTIAL_KEY for the env"
              + " fallback. See docs/ops/runbooks/kek-config.md.");
    }

    return composed;
  }

  /**
   * Resolves the {@link DevAutoKeyProvider} CDI bean, returning {@code null} if construction
   * refused (prod profile) or the lookup is otherwise unresolvable. This is the boundary where the
   * #1076 fail-closed semantics meet the producer chain: the dev bean simply does not appear in the
   * chain on prod boots.
   */
  private DevAutoKeyProvider tryGetDevAuto() {
    if (devAutoLookup == null || devAutoLookup.isUnsatisfied()) {
      return null;
    }
    try {
      return devAutoLookup.get();
    } catch (RuntimeException e) {
      // Unwrap CDI's wrapper to find the underlying ConfigException, if any — gives operators a
      // pointed log line ("DevAutoKeyProvider refused construction: <msg>") instead of a generic
      // ContextException stack trace from Arc.
      Throwable cause = e;
      while (cause != null && !(cause instanceof ConfigException) && cause.getCause() != null) {
        cause = cause.getCause();
      }
      String msg = cause != null ? cause.getMessage() : e.getMessage();
      LOGGER.log(
          Level.INFO,
          "[titan] DevAutoKeyProvider not available in this profile (skipping): {0}",
          msg);
      return null;
    }
  }

  /**
   * Inlined first-non-null chain — duplicates {@code ChainedCredentialKeyProvider} (package-private
   * in titan-pipeline-model) so we do not need to widen its visibility for a server-only
   * composition concern.
   */
  static final class ProducerChain implements CredentialKeyProvider {
    private final List<CredentialKeyProvider> chain;

    ProducerChain(List<CredentialKeyProvider> chain) {
      this.chain = chain;
    }

    @Override
    public byte[] credentialKey() {
      for (CredentialKeyProvider p : chain) {
        try {
          byte[] key = p.credentialKey();
          if (key != null) {
            return key;
          }
        } catch (RuntimeException e) {
          LOGGER.log(
              Level.WARNING,
              e,
              () ->
                  "[titan] credential-key provider "
                      + p.describe()
                      + " threw; trying next link in chain");
        }
      }
      return null;
    }

    @Override
    public String describe() {
      StringBuilder sb = new StringBuilder("chain[");
      for (int i = 0; i < chain.size(); i++) {
        if (i > 0) {
          sb.append(" -> ");
        }
        sb.append(chain.get(i).describe());
      }
      return sb.append(']').toString();
    }

    @Override
    public int credentialKeyVersion() {
      for (CredentialKeyProvider p : chain) {
        try {
          if (p.credentialKey() != null) {
            return p.credentialKeyVersion();
          }
        } catch (RuntimeException ignored) {
          // skip
        }
      }
      return 1;
    }

    @Override
    public byte[] credentialKeyByVersion(int version) {
      for (CredentialKeyProvider p : chain) {
        try {
          byte[] key = p.credentialKeyByVersion(version);
          if (key != null) {
            return key;
          }
        } catch (RuntimeException ignored) {
          // skip
        }
      }
      return null;
    }
  }
}
