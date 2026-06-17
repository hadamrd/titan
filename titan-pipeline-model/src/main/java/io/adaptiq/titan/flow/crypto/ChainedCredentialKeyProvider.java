package io.adaptiq.titan.flow.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A {@link CredentialKeyProvider} that resolves the credential-sealing key from an ordered chain of
 * providers, returning the first link that yields a key.
 *
 * <p><strong>Why this exists.</strong> {@link CredentialKeyProvider#active()} previously returned
 * the first {@link java.util.ServiceLoader} provider <em>unconditionally</em>. A provider that is
 * installed and connected but whose key secret is absent — e.g. a KMS/vault provider pointed at a
 * project that has no {@code TITAN_CREDENTIAL_KEY} secret — returns {@code null} from {@link
 * CredentialKeyProvider#credentialKey()}, and the engine then fails closed, even though the
 * built-in {@link EnvCredentialKeyProvider} (or another provider) could have supplied the key. A
 * configured-but-keyless provider silently shadowed a working one.
 *
 * <p>The chain fixes that: {@link #credentialKey()} walks the providers in order and returns the
 * first non-{@code null} key; a link that returns {@code null} — or throws — is skipped and the
 * next is tried. The built-in {@link EnvCredentialKeyProvider} is always the last link, so a
 * deployment that sets {@code TITAN_CREDENTIAL_KEY} is honoured even when a higher-priority
 * provider is misconfigured. Only when <em>no</em> link yields a key does {@link #credentialKey()}
 * return {@code null} and the caller fail closed (design/39 §5).
 */
final class ChainedCredentialKeyProvider implements CredentialKeyProvider {

  private static final Logger LOGGER =
      Logger.getLogger(ChainedCredentialKeyProvider.class.getName());

  private final List<CredentialKeyProvider> chain;

  ChainedCredentialKeyProvider(@NonNull List<CredentialKeyProvider> chain) {
    this.chain = List.copyOf(chain);
  }

  @Override
  @Nullable
  public byte[] credentialKey() {
    for (CredentialKeyProvider provider : chain) {
      byte[] key;
      try {
        key = provider.credentialKey();
      } catch (RuntimeException e) {
        // A misconfigured provider must not block a working fallback link.
        LOGGER.log(
            Level.WARNING,
            e,
            () ->
                "[titan] credential-key provider "
                    + provider.describe()
                    + " failed; trying the next link in the chain");
        continue;
      }
      if (key != null) {
        return key;
      }
      LOGGER.log(
          Level.FINE,
          () ->
              "[titan] credential-key provider "
                  + provider.describe()
                  + " yielded no key; trying the next link in the chain");
    }
    return null; // no link yielded a key — the caller fails closed (design/39 §5)
  }

  @Override
  @NonNull
  public String describe() {
    return chain.stream()
        .map(CredentialKeyProvider::describe)
        .collect(Collectors.joining(" -> ", "chain[", "]"));
  }
}
