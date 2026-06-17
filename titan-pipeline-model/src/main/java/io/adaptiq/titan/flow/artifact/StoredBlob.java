package io.adaptiq.titan.flow.artifact;

/**
 * The outcome of an {@link ArtifactStore#put} — what the caller records in the {@code
 * titan.artifact} row (design/41 §3.1, 32E-2).
 *
 * <p>The store, not the caller, decides the {@code storageRef}: a filesystem store returns a
 * relative path, an S3 store an object key, the Postgres store a large-object oid. A read later
 * goes back through {@link ArtifactStore#open(String)} with this exact value — the store is the
 * sole authority on its own locator format.
 *
 * @param sizeBytes the number of bytes stored
 * @param sha256 the lowercase-hex SHA-256 of the content — Titan's content fingerprint (design/41
 *     §8.3); computed by the store while streaming, so it is never a second pass over the bytes
 * @param storageRef the backend-specific locator, opaque to the caller, persisted verbatim in
 *     {@code titan.artifact.storage_ref}
 */
public record StoredBlob(long sizeBytes, String sha256, String storageRef) {

  public StoredBlob {
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("sizeBytes must not be negative: " + sizeBytes);
    }
    if (sha256 == null || sha256.length() != 64) {
      throw new IllegalArgumentException("sha256 must be 64 hex chars: " + sha256);
    }
    if (storageRef == null || storageRef.isBlank()) {
      throw new IllegalArgumentException("storageRef must not be blank");
    }
  }
}
