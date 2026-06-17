package io.adaptiq.titan.flow.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * Resolves a <strong>synthesis-time secret</strong> — by name — from an external secret manager
 * (design/40 §3). A pluggable seam, the worker-side sibling of {@link CredentialKeyProvider}: the
 * same shape, the same {@link ServiceLoader} discovery, the same fail-closed contract.
 *
 * <p><strong>Why this is not {@code design/39}.</strong> {@code design/39} (D6) resolves a
 * <em>step's</em> credentials on the controller, against the credential store, and seals them into
 * the step payload. A {@code library()} call (design/38 Stage&nbsp;2b) is different: it runs
 * <em>during synthesis</em>, on a worker, <em>before</em> the DAG exists — so the controller cannot
 * know ahead of time which library, or which credential, the program will ask for, and there is no
 * controller round-trip available mid-synthesis (design/26 Tier&nbsp;C). A synthesis-time
 * credential is therefore resolved <em>on the worker</em>, when {@code library()} runs, from an
 * external secret manager the worker is configured to reach (design/40 §2). That external manager —
 * Infisical, Vault, a cloud KMS — is <em>not</em> the controller's credential store; a worker
 * holding a scoped, read-only token to it is the standard CI-runner posture and no weakening of D6.
 *
 * <p><strong>Why an SPI.</strong> Titan ships an <em>interface</em> plus an Infisical reference
 * implementation; an organisation points it at the secret manager it already runs. A Vault- or
 * KMS-backed provider is a drop-in jar carrying a {@code META-INF/services} entry — no engine
 * change, exactly as {@link CredentialKeyProvider}.
 *
 * <p>This interface lives in {@code titan-pipeline-model} — the shared module the worker runs
 * synthesis from — so {@link io.adaptiq.titan.flow.parser.LibraryFetcher} can call it directly.
 */
public interface SecretProvider {

  /**
   * The value of the named secret.
   *
   * @param name the secret's name in the external manager — <em>not</em> a credential-store id,
   *     <em>not</em> the secret value. This is what a synthesis program's {@code library(...,
   *     credential: 'name')} carries; the synthesis program is data that lands in {@code
   *     pipeline_model_json} and must never contain a secret (design/40 §4).
   * @return the secret's value, or {@code null} if this provider does not hold it (or is not
   *     configured). A {@code null} must <strong>fail synthesis closed</strong> (design/40 §4):
   *     {@code library()} fails with a clear, located message — never a silent fall-through to an
   *     unauthenticated fetch.
   */
  @Nullable
  String secret(@NonNull String name);

  /**
   * A short, non-sensitive description of where secrets come from, for diagnostics and logs — e.g.
   * {@code "infisical:<project>/prod/"} or {@code "noop"}. Never returns a secret value.
   */
  @NonNull
  String describe();

  /**
   * The active provider: the first one a {@link ServiceLoader} finds on the classpath, else the
   * built-in {@link NoopSecretProvider}. A worker that installs an Infisical (or Vault, or KMS)
   * provider jar gets it automatically; one that installs none gets the no-op provider, under which
   * any {@code credential:}-bearing {@code library()} call fails closed.
   */
  @NonNull
  static SecretProvider active() {
    Iterator<SecretProvider> found = ServiceLoader.load(SecretProvider.class).iterator();
    return found.hasNext() ? found.next() : new NoopSecretProvider();
  }
}
