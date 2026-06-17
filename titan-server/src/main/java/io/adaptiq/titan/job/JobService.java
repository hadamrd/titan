package io.adaptiq.titan.job;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.Optional;

/**
 * Domain-facing API for Titan jobs — CRUD over the {@code titan.jobs} table. The Quarkus REST layer
 * in {@link io.adaptiq.titan.api.JobsApi} calls these methods directly. Errors at the storage layer
 * surface as {@link io.adaptiq.titan.db.TitanDataException}.
 */
public interface JobService {

  @NonNull
  Optional<Job> findById(long id);

  @NonNull
  Optional<Job> findByFullName(@NonNull String fullName);

  /**
   * Return every job in {@code titan.jobs}, ordered by {@code full_name} ascending. The HTTP layer
   * paginates this in memory; a future SQL-level overload can be added when total counts grow.
   */
  @NonNull
  List<Job> listAll();

  /**
   * Return every job paired with a summary of its most-recent build (issue #529). Jobs that have
   * never run carry a {@code null} {@link JobWithLastBuild#lastBuild()}. Ordered by {@code
   * full_name} ascending — matches {@link #listAll()} so per-row offsets line up across both
   * variants.
   */
  @NonNull
  List<JobWithLastBuild> listAllWithLastBuild();

  /**
   * Server-side substring search variant of {@link #listAllWithLastBuild()} (closes #693). The
   * {@code search} parameter matches case-insensitively against the job's {@code full_name} OR
   * {@code display_name}. A {@code null}/blank token degenerates to the unfiltered call so callers
   * can pass through the raw query-string value without branching.
   *
   * <p>The default implementation does an in-memory case-insensitive filter so legacy {@link
   * JobService} stubs (test fakes, etc.) keep working without code changes — only the production
   * {@link JobServiceImpl} overrides this to push the filter down to SQL.
   */
  @NonNull
  default List<JobWithLastBuild> listAllWithLastBuild(@Nullable String search) {
    List<JobWithLastBuild> all = listAllWithLastBuild();
    if (search == null || search.isBlank()) {
      return all;
    }
    String needle = search.trim().toLowerCase(java.util.Locale.ROOT);
    return all.stream()
        .filter(
            jwlb -> {
              Job j = jwlb.job();
              String full =
                  j.fullName() == null ? "" : j.fullName().toLowerCase(java.util.Locale.ROOT);
              String disp =
                  j.displayName() == null ? "" : j.displayName().toLowerCase(java.util.Locale.ROOT);
              return full.contains(needle) || disp.contains(needle);
            })
        .toList();
  }

  /**
   * Materialise a new job row. Returns the persisted record, including the database-assigned id and
   * timestamps.
   */
  @NonNull
  Job create(@NonNull NewJobRequest request);

  /**
   * Apply the mutable subset of fields to an existing job row. Returns the refreshed record.
   *
   * @throws JobNotFoundException if no row exists for {@code id}.
   */
  @NonNull
  Job update(long id, @NonNull JobUpdate update);

  /**
   * Remove a job row. Cascades to {@code titan.builds} and the build's child tables. No-op if
   * absent.
   */
  void delete(long id);
}
