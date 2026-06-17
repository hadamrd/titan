package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Input record for {@code CredentialsService.create(...)} and {@link SecretsBackend#create}.
 *
 * <p>{@link #plaintext} is the secret to seal. It is consumed by the service implementation, sealed
 * via the host's {@code SecretCipher} and never persisted as-is. Callers must clear or drop
 * references to it after the call returns.
 *
 * <p>{@link #kind} must be one of {@link Credential#KIND_USERNAME_PASSWORD}, {@link
 * Credential#KIND_SSH_KEY}, {@link Credential#KIND_STRING}, {@link Credential#KIND_FILE}. {@link
 * #scope} is a free-form selector — {@code "global"}, {@code "folder:/foo"}, {@code
 * "job:my-pipeline"}.
 */
public record NewCredentialRequest(
    @NonNull String kind, @NonNull String scope, @NonNull String key, @NonNull String plaintext) {}
