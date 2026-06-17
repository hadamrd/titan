package io.adaptiq.titan.flow.crypto;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * The built-in {@link SecretProvider} — holds no secrets. {@link #secret(String)} always returns
 * {@code null}.
 *
 * <p>This is the default when a worker has no secret-manager provider jar on its classpath
 * (design/40 §3). It is deliberately inert rather than reading an environment variable: a
 * synthesis-time credential is sourced from an <em>external secret manager</em>, not from the
 * worker's environment, so there is no honest no-op behaviour to offer beyond "I hold nothing".
 *
 * <p>Under this provider any {@code library(..., credential: '<name>')} call fails synthesis closed
 * (design/40 §4) — {@link io.adaptiq.titan.flow.parser.LibraryFetcher} sees a {@code null} secret
 * and raises a clear, located error. A {@code library()} call with no {@code credential:} argument
 * — a public repository — is unaffected.
 */
public final class NoopSecretProvider implements SecretProvider {

  @Override
  @Nullable
  public String secret(@NonNull String name) {
    return null; // holds nothing — a credential: library() then fails closed (design/40 §4)
  }

  @Override
  @NonNull
  public String describe() {
    return "noop";
  }
}
