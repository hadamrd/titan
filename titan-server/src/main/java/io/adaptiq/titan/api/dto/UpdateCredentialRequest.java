package io.adaptiq.titan.api.dto;

/**
 * Wire format for {@code PUT /api/v1/credentials/{id}}: rotate the secret value (and optionally the
 * kind). {@code scope} and {@code key} are immutable post-creation, so they are not part of this
 * request.
 */
public record UpdateCredentialRequest(String kind, String plaintext) {}
