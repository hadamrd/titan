package io.adaptiq.titan.api.dto;

/**
 * Wire format for {@code POST /api/v1/credentials}: a client supplies plaintext, the server seals
 * it under {@link io.adaptiq.titan.flow.crypto.SecretCipher#seal} before persisting.
 *
 * <p>{@code kind} must be one of {@link io.adaptiq.titan.credentials.Credential#KIND_STRING},
 * {@link io.adaptiq.titan.credentials.Credential#KIND_USERNAME_PASSWORD}, {@link
 * io.adaptiq.titan.credentials.Credential#KIND_SSH_KEY}, {@link
 * io.adaptiq.titan.credentials.Credential#KIND_FILE}.
 */
public record CreateCredentialRequest(String kind, String scope, String key, String plaintext) {}
