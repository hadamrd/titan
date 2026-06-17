package io.adaptiq.titan.job;

/**
 * Thrown by {@link JobService#update(long, JobUpdate)} when no row exists for the given id. A
 * {@code RuntimeException} so the storage-layer convention of unchecked errors is preserved.
 */
public class JobNotFoundException extends RuntimeException {
  public JobNotFoundException(long id) {
    super("job " + id + " not found");
  }
}
