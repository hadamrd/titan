package io.adaptiq.titan.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.build.Build;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.build.BuildUpdate;
import io.adaptiq.titan.build.NewBuildRequest;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.job.JobUpdate;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for the three Quarkus Cache wirings on {@code titan-server}:
 *
 * <ul>
 *   <li>{@code pipeline-model} — keyed by buildId, invalidated on terminal write / update / delete.
 *   <li>{@code job-lookup} — keyed by jobId, invalidated on update / delete.
 *   <li>{@code cron-compile} — config-only in this PR; we only assert the cache is present.
 * </ul>
 *
 * <p>Strategy: drive the cache through the CDI service surface (so the {@code @CacheResult
 * / @CacheInvalidate} interceptors actually fire), and use the {@code @CacheName}-injected {@link
 * Cache} handle to count live entries / invalidate from the outside.
 */
@QuarkusTest
class CacheBehaviorTest {

  @Inject TitanStores stores;
  @Inject BuildService buildService;
  @Inject JobService jobService;

  /**
   * Deliberately NOT {@code @Inject}-ed (#37): an injection point — even one in test sources —
   * counts as a "user" during ARC's unused-bean removal and would keep the bean alive under
   * {@code @QuarkusTest} while production (where nothing injects it) silently loses it.
   * Programmatic lookup here mirrors production and keeps the removability oracle honest — see
   * {@link PipelineModelCacheResolvabilityTest}.
   */
  private PipelineModelCache pipelineModelCache;

  @Inject
  @CacheName("pipeline-model")
  Cache pipelineModelCacheHandle;

  @Inject
  @CacheName("job-lookup")
  Cache jobLookupCacheHandle;

  @Inject
  @CacheName("cron-compile")
  Cache cronCompileCacheHandle;

  // Trivial PipelineModel JSON — empty stages/gates/etc. Jackson parses this fine.
  private static final String EMPTY_PIPELINE_JSON =
      "{\"stages\":[],\"gates\":[],\"preconditions\":[],\"parameters\":[]}";

  @BeforeEach
  void clearAll() {
    pipelineModelCache =
        jakarta.enterprise.inject.spi.CDI.current().select(PipelineModelCache.class).get();
    // Each test starts with all caches empty — @ApplicationScoped beans are shared across tests.
    pipelineModelCacheHandle.invalidateAll().await().indefinitely();
    jobLookupCacheHandle.invalidateAll().await().indefinitely();
    cronCompileCacheHandle.invalidateAll().await().indefinitely();
  }

  // ── pipeline-model ────────────────────────────────────────────────────────

  @Test
  void pipelineModelCache_hitsOnSecondLoad() {
    long buildId = freshBuildWithModel();

    PipelineModel first = pipelineModelCache.load(buildId);
    PipelineModel second = pipelineModelCache.load(buildId);

    assertNotNull(first);
    // Caffeine returns the same instance on a hit — that is the strongest possible cache assertion.
    assertSame(first, second, "second load must come from cache");
  }

  @Test
  void pipelineModelCache_invalidatesOnBuildServiceUpdate() {
    long buildId = freshBuildWithModel();

    PipelineModel first = pipelineModelCache.load(buildId);
    assertNotNull(first);

    // BuildService.update is annotated @CacheInvalidate — this is the API-driven terminal path.
    buildService.update(buildId, new BuildUpdate("SUCCESS", null, null, null, null));

    PipelineModel second = pipelineModelCache.load(buildId);
    assertNotSame(first, second, "update() must drop the cached model");
  }

  @Test
  void pipelineModelCache_invalidatesOnBuildServiceDelete() {
    long buildId = freshBuildWithModel();
    pipelineModelCache.load(buildId);
    assertTrue(cacheContains(pipelineModelCacheHandle, buildId));

    buildService.delete(buildId);
    assertTrue(!cacheContains(pipelineModelCacheHandle, buildId), "delete() must drop the entry");
  }

  @Test
  void pipelineModelCache_invalidateIfActive_dropsEntry() {
    long buildId = freshBuildWithModel();
    pipelineModelCache.load(buildId);
    assertTrue(cacheContains(pipelineModelCacheHandle, buildId));

    // Engine-side terminal-write path: BuildAbortService / TitanOrchestrator.finishIfDone /
    // QueueProcessor.markBuildFailed all call this static helper. CDI is up under @QuarkusTest,
    // so this resolves the bean and invokes the @CacheInvalidate method.
    PipelineModelCache.invalidateIfActive(buildId);

    assertTrue(
        !cacheContains(pipelineModelCacheHandle, buildId),
        "invalidateIfActive must drop the entry");
  }

  // ── job-lookup ────────────────────────────────────────────────────────────

  @Test
  void jobLookupCache_hitsOnSecondFindById() {
    long jobId = freshJob();

    Optional<Job> first = jobService.findById(jobId);
    Optional<Job> second = jobService.findById(jobId);

    assertTrue(first.isPresent());
    assertTrue(second.isPresent());
    // Both refer to the same cached Optional instance.
    assertSame(first, second, "second findById must come from cache");
  }

  @Test
  void jobLookupCache_invalidatesOnUpdate() {
    long jobId = freshJob();

    Optional<Job> before = jobService.findById(jobId);
    assertTrue(before.isPresent());
    assertEquals("orig", before.get().displayName());

    // update() is annotated @CacheInvalidate.
    jobService.update(jobId, new JobUpdate("renamed", null, "", "{}", true));

    Optional<Job> after = jobService.findById(jobId);
    assertTrue(after.isPresent());
    assertEquals("renamed", after.get().displayName(), "update must drop cache; rename visible");
    assertNotSame(before, after, "update must drop cache");
  }

  @Test
  void jobLookupCache_invalidatesOnDelete() {
    long jobId = freshJob();
    jobService.findById(jobId);
    assertTrue(cacheContains(jobLookupCacheHandle, jobId));

    jobService.delete(jobId);
    assertTrue(!cacheContains(jobLookupCacheHandle, jobId), "delete must drop the cache entry");
  }

  // ── cron-compile (config-only) ───────────────────────────────────────────

  @Test
  void cronCompileCache_isConfiguredAndUsable() {
    // Wave 1 (feat/port-trigger-framework) will annotate CronSchedule.compile(String). This PR
    // ships the config only; the smoke test asserts the cache exists, accepts puts, and obeys
    // its key contract — so Wave 1 can plug the annotation in with one line.
    assertEquals("cron-compile", cronCompileCacheHandle.getName());
    cronCompileCacheHandle
        .as(io.quarkus.cache.CaffeineCache.class)
        .put("0 * * * *", java.util.concurrent.CompletableFuture.completedFuture("compiled"));
    assertTrue(
        cronCompileCacheHandle
            .as(io.quarkus.cache.CaffeineCache.class)
            .keySet()
            .contains("0 * * * *"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /** Insert a job + a build with a populated {@code pipeline_model_json}, return the build id. */
  private long freshBuildWithModel() {
    long jobId = freshJob();
    Build created =
        buildService.create(
            new NewBuildRequest(jobId, null, "test", "test", null, EMPTY_PIPELINE_JSON, null));
    return created.id();
  }

  private long freshJob() {
    JobRow row = new JobRow();
    row.fullName = "cache-behavior-test/" + System.nanoTime();
    row.displayName = "orig";
    row.pipelineScript = "";
    row.configJson = "{}";
    row.enabled = true;
    return stores.jobs().insert(row);
  }

  private static boolean cacheContains(Cache cache, Object key) {
    return cache.as(io.quarkus.cache.CaffeineCache.class).keySet().contains(key);
  }
}
