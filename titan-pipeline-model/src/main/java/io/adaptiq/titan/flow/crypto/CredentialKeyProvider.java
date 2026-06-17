package io.adaptiq.titan.flow.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Supplies the AES-256 key that {@link SecretCipher} uses to seal credential payloads (design/39
 * §3.1). A pluggable seam — the key source is deployment policy, not engine code.
 *
 * <p><strong>Why an SPI.</strong> A static key in process configuration is the right default for a
 * single team; larger or regulated deployments want the key behind a KMS / vault — AWS KMS, Azure
 * Key Vault, HashiCorp Vault, GCP KMS — exactly as Kubernetes etcd encryption and the major CI
 * systems do. Rather than hard-wire one answer, the key source is a standard Java {@link
 * ServiceLoader} service: the default {@link EnvCredentialKeyProvider} ships in the box; a
 * KMS-backed provider is a drop-in jar carrying a {@code META-INF/services} entry — no change to
 * the controller or the worker.
 *
 * <p>The same provider must yield the same key on the controller (which seals) and on every worker
 * (which unseals); both call {@link #active()} independently.
 */
public interface CredentialKeyProvider {

  /**
   * The credential-sealing key.
   *
   * @return a {@value SecretCipher#KEY_LENGTH_BYTES}-byte AES-256 key, or {@code null} if no key is
   *     configured — callers must then <strong>fail closed</strong> (design/39 §5): a step
   *     declaring {@code credentials:} is failed rather than dispatched with an unprotected
   *     payload.
   */
  @Nullable
  byte[] credentialKey();

  /**
   * A short, non-sensitive description of where the key comes from, for diagnostics and logs — e.g.
   * {@code "env:TITAN_CREDENTIAL_KEY"}. Never returns key material.
   */
  @NonNull
  String describe();

  /**
   * The version of the currently-active key — used by envelope-encrypting backends (see {@code
   * DbEnvelopeBackend}) to stamp the {@code kek_version} column. Providers that do not version
   * their keys may keep returning {@code 1}; rotation through such a provider is then a
   * recreate-every-row operation (the V10 fallback path), not the cheap re-wrap path.
   *
   * <p>Default: {@code 1}. Providers that support rotation override this — typically driven by the
   * KMS / vault that owns the key material.
   */
  default int credentialKeyVersion() {
    return 1;
  }

  /**
   * Look up an OLD KEK version, by number, during a rotation. The provider returns the key bytes
   * for the given version, or {@code null} if that version is no longer accessible — in which case
   * the backend logs and skips re-wrapping that row.
   *
   * <p>Default: returns the active key when {@code version == credentialKeyVersion()}, else {@code
   * null}. Providers that retain rotated-out keys override this to keep recent versions available
   * long enough to complete a rotation pass.
   */
  @Nullable
  default byte[] credentialKeyByVersion(int version) {
    return version == credentialKeyVersion() ? credentialKey() : null;
  }

  /**
   * The active provider: a chain of every {@link ServiceLoader}-discovered provider, in discovery
   * order, with the built-in {@link EnvCredentialKeyProvider} always appended as the last-resort
   * link. {@link #credentialKey()} returns the first link that yields a key.
   *
   * <p>This is a <strong>chain</strong>, not "the first provider found", deliberately: a discovered
   * provider that is installed and connected but whose key secret is absent returns {@code null},
   * and must not shadow a working fallback. A deployment that installs a KMS-backed provider jar
   * gets it as the first link; if that link has no key, the environment-variable default still
   * applies. Only when no link yields a key does the caller fail closed (design/39 §5). When
   * nothing is discovered the chain is just the env default, which is returned directly — behaviour
   * identical to the original single-provider lookup.
   */
  @NonNull
  static CredentialKeyProvider active() {
    List<CredentialKeyProvider> chain = new ArrayList<>();
    ServiceLoader.load(CredentialKeyProvider.class).forEach(chain::add);
    chain.add(new EnvCredentialKeyProvider());
    return chain.size() == 1 ? chain.get(0) : new ChainedCredentialKeyProvider(chain);
  }
}
