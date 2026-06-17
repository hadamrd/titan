package io.adaptiq.titan.job;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.store.rows.JobWithLastBuildRow;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/**
 * JDBI-backed {@link JobService}. Translates between the persistent {@link JobRow} POJO and the
 * domain {@link Job} record so callers never see the storage layer's mutable row type.
 *
 * <p>Constructor-injection only: {@link TitanStores} is produced by {@link
 * io.adaptiq.titan.boot.StoresProducer}; this class is wired as an {@code @ApplicationScoped} CDI
 * bean (Quarkus ARC builds the proxy at compile time — no reflective autowire).
 */
@ApplicationScoped
public class JobServiceImpl implements JobService {

  private final TitanStores stores;

  public JobServiceImpl(TitanStores stores) {
    this.stores = stores;
  }

  // ── reads ───────────────────────────────────────────────────────────────────

  /**
   * {@inheritDoc}
   *
   * <p>Cache: {@code job-lookup} — keyed by {@code id}, 10s TTL (see {@code
   * application.properties}). Hot trigger / orchestrator paths re-hit this within a tick burst; the
   * short TTL amortises the burst without staleness being observable cross-tick. Invalidated on
   * {@link #update} and {@link #delete}.
   */
  @Override
  @NonNull
  @CacheResult(cacheName = "job-lookup")
  public Optional<Job> findById(@CacheKey long id) {
    return stores.jobs().findById(id).map(JobServiceImpl::toDomain);
  }

  @Override
  @NonNull
  public Optional<Job> findByFullName(@NonNull String fullName) {
    return stores.jobs().findByFullName(fullName).map(JobServiceImpl::toDomain);
  }

  @Override
  @NonNull
  public List<Job> listAll() {
    return stores.jobs().listAll().stream().map(JobServiceImpl::toDomain).toList();
  }

  @Override
  @NonNull
  public List<JobWithLastBuild> listAllWithLastBuild() {
    return stores.jobs().listAllWithLastBuild().stream()
        .map(JobServiceImpl::toDomainWithLastBuild)
        .toList();
  }

  /**
   * {@inheritDoc}
   *
   * <p>Blank/null {@code search} short-circuits to the unfiltered DAO call so the cache-friendly,
   * already-optimised query is reused. A non-blank token is wrapped in {@code %...%} and bound as a
   * JDBI parameter (never concatenated) — SQL-injection payloads degenerate to a literal substring
   * match that finds nothing.
   */
  @Override
  @NonNull
  public List<JobWithLastBuild> listAllWithLastBuild(@Nullable String search) {
    if (search == null || search.isBlank()) {
      return listAllWithLastBuild();
    }
    String pattern = "%" + search.trim() + "%";
    return stores.jobs().searchAllWithLastBuild(pattern).stream()
        .map(JobServiceImpl::toDomainWithLastBuild)
        .toList();
  }

  // ── writes ──────────────────────────────────────────────────────────────────

  @Override
  @NonNull
  public Job create(@NonNull NewJobRequest request) {
    JobRow row = new JobRow();
    row.fullName = request.fullName();
    row.displayName = request.displayName();
    row.folderPath = request.folderPath();
    row.pipelineScript = request.pipelineScript() != null ? request.pipelineScript() : "";
    row.configJson = request.configJson() != null ? request.configJson() : "{}";
    row.createdBy = request.createdBy();
    row.enabled = request.enabled();
    long id = stores.jobs().insert(row);
    // Re-read so timestamps (CURRENT_TIMESTAMP defaults) come back filled in.
    return stores
        .jobs()
        .findById(id)
        .map(JobServiceImpl::toDomain)
        .orElseThrow(() -> new IllegalStateException("inserted job " + id + " not retrievable"));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Cache: invalidates {@code job-lookup} for {@code id} so a subsequent {@link #findById} sees
   * the rewritten row.
   */
  @Override
  @NonNull
  @CacheInvalidate(cacheName = "job-lookup")
  public Job update(@CacheKey long id, @NonNull JobUpdate update) {
    JobRow existing = stores.jobs().findById(id).orElseThrow(() -> new JobNotFoundException(id));
    existing.displayName = update.displayName();
    existing.folderPath = update.folderPath();
    existing.pipelineScript = update.pipelineScript() != null ? update.pipelineScript() : "";
    existing.configJson = update.configJson() != null ? update.configJson() : "{}";
    existing.enabled = update.enabled();
    stores.jobs().update(existing);
    return stores
        .jobs()
        .findById(id)
        .map(JobServiceImpl::toDomain)
        .orElseThrow(() -> new JobNotFoundException(id));
  }

  /**
   * {@inheritDoc}
   *
   * <p>Cache: invalidates {@code job-lookup} for {@code id} — the row is gone after this call.
   */
  @Override
  @CacheInvalidate(cacheName = "job-lookup")
  public void delete(@CacheKey long id) {
    stores.jobs().delete(id);
  }

  // ── mapping ─────────────────────────────────────────────────────────────────

  /** Translate a storage row to the domain record. Defensive nulls for the text fields. */
  @NonNull
  static Job toDomain(@NonNull JobRow row) {
    return new Job(
        row.id,
        row.fullName,
        row.displayName,
        row.folderPath,
        row.pipelineScript != null ? row.pipelineScript : "",
        row.configJson != null ? row.configJson : "{}",
        row.createdBy,
        row.createdAt,
        row.updatedAt,
        row.enabled);
  }

  /**
   * Translate a flat job+last-build row (issue #529) into the {@link JobWithLastBuild} domain pair.
   * The last-build summary is {@code null} when {@code lastBuildId} is null — i.e. when the {@code
   * LEFT JOIN} found no build for the job.
   */
  @NonNull
  static JobWithLastBuild toDomainWithLastBuild(@NonNull JobWithLastBuildRow row) {
    Job job =
        new Job(
            row.id,
            row.fullName,
            row.displayName,
            row.folderPath,
            row.pipelineScript != null ? row.pipelineScript : "",
            row.configJson != null ? row.configJson : "{}",
            row.createdBy,
            row.createdAt,
            row.updatedAt,
            row.enabled);
    JobWithLastBuild.LastBuildSummary summary =
        (row.lastBuildId == null || row.lastBuildNumber == null || row.lastBuildStatus == null)
            ? null
            : new JobWithLastBuild.LastBuildSummary(
                row.lastBuildId,
                row.lastBuildNumber,
                row.lastBuildStatus,
                row.lastBuildDurationMs,
                row.lastBuildFinishedAt);
    return new JobWithLastBuild(job, summary);
  }
}
