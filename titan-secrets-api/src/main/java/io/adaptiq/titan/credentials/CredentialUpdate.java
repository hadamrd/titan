package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Input record for {@link SecretsBackend#update(long, CredentialUpdate)} (and the host {@code
 * CredentialsService}). Updates the plaintext (re-seals on write) and may change the {@code kind}
 * of the existing row.
 *
 * <p>{@code scope} and {@code key} are immutable for the lifetime of a credential — the unique
 * index on {@code (scope, key)} is the identity used by {@link SecretsBackend#resolvePlaintext}.
 */
public record CredentialUpdate(@NonNull String kind, @NonNull String plaintext) {}
