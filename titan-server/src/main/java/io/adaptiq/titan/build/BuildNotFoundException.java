package io.adaptiq.titan.build;

/**
 * Thrown by {@link BuildService#update(long, BuildUpdate)} (and similar lookups) when no row exists
 * for the given id. Unchecked so the storage-layer convention is preserved.
 */
public class BuildNotFoundException extends RuntimeException {
  public BuildNotFoundException(long id) {
    super("build " + id + " not found");
  }
}
