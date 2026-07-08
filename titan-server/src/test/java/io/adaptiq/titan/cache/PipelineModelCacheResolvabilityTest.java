package io.adaptiq.titan.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.build.Build;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.build.NewBuildRequest;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Adversarial oracle for #37 — ARC unused-bean removal of {@link PipelineModelCache}.
 *
 * <p>Nothing in production {@code @Inject}s {@link PipelineModelCache}; every engine call site
 * reaches it via the static {@code CDI.current()} lookups. Before the fix, ARC removed the bean at
 * build time, every lookup logged a "programmatic lookup problem detected" WARN (126x per smoke
 * run) and the callers silently fell back to uncached parsing.
 *
 * <p><strong>This test must NOT {@code @Inject} the bean.</strong> An injection point — even in
 * test sources — marks the bean as "used" for the whole test augmentation and would mask the
 * production removal (which is exactly why {@code CacheBehaviorTest}'s old {@code @Inject} never
 * caught the bug). If someone strips {@code @Unremovable} and the startup observer from the bean,
 * the programmatic lookup below becomes unresolvable again and this test fails.
 */
@QuarkusTest
class PipelineModelCacheResolvabilityTest {

  @Inject TitanStores stores;
  @Inject BuildService buildService;

  @Inject
  @CacheName("pipeline-model")
  Cache pipelineModelCacheHandle;

  private static final String EMPTY_PIPELINE_JSON =
      "{\"stages\":[],\"gates\":[],\"preconditions\":[],\"parameters\":[]}";

  /** Captures everything the ARC runtime logs while the test body runs. */
  private final List<LogRecord> arcRecords = new CopyOnWriteArrayList<>();

  private final Logger arcLogger = Logger.getLogger("io.quarkus.arc");
  private Handler captureHandler;

  @BeforeEach
  void setUp() {
    arcRecords.clear();
    captureHandler =
        new Handler() {
          @Override
          public void publish(LogRecord record) {
            arcRecords.add(record);
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    arcLogger.addHandler(captureHandler);
    pipelineModelCacheHandle.invalidateAll().await().indefinitely();
  }

  @AfterEach
  void tearDown() {
    arcLogger.removeHandler(captureHandler);
  }

  @Test
  void beanResolvesProgrammatically_andSecondLoadHitsCache_withZeroLookupWarnings() {
    // 1) Resolvability — the exact lookup production performs. Fails if ARC removed the bean.
    Instance<PipelineModelCache> lookup = CDI.current().select(PipelineModelCache.class);
    assertTrue(
        lookup.isResolvable(),
        "PipelineModelCache must be resolvable via programmatic lookup. ARC removed it as unused"
            + " (#37) — restore @Unremovable and the StartupEvent observer on the bean.");
    PipelineModelCache cache = lookup.get();
    long parsesBefore = cache.parseCount();

    // 2) Load the SAME pipeline model twice through the production entry point.
    long buildId = freshBuildWithModel();
    PipelineModel first = PipelineModelCache.loadOrFresh(stores, buildId);
    PipelineModel second = PipelineModelCache.loadOrFresh(stores, buildId);
    assertNotNull(first);

    // 3) Cache-hit oracle: Caffeine returns the same instance on a hit, and the parse counter
    //    (incremented only inside the real load body, which @CacheResult short-circuits on a hit)
    //    must have moved by exactly one.
    assertSame(first, second, "second loadOrFresh must be served from the pipeline-model cache");
    assertEquals(
        parsesBefore + 1,
        cache.parseCount(),
        "exactly one real parse expected — the second load must hit the cache, not re-parse");

    // 4) invalidateIfActive must ride the live bean too: after invalidation the next load
    //    re-parses (counter moves), proving the static invalidation path is operative.
    PipelineModelCache.invalidateIfActive(buildId);
    PipelineModelCache.loadOrFresh(stores, buildId);
    assertEquals(
        parsesBefore + 2,
        cache.parseCount(),
        "load after invalidateIfActive must re-parse — invalidation path must be operative");

    // 5) Zero ARC lookup noise: the 126x-per-run WARN storm must be gone.
    List<String> lookupProblems =
        arcRecords.stream()
            .map(LogRecord::getMessage)
            .filter(m -> m != null)
            .filter(m -> m.toLowerCase(Locale.ROOT).contains("programmatic lookup problem"))
            .collect(Collectors.toList());
    assertTrue(
        lookupProblems.isEmpty(),
        "expected zero 'programmatic lookup problem' warnings from ARC, got: " + lookupProblems);
  }

  // ── helpers (mirrors CacheBehaviorTest) ────────────────────────────────────

  private long freshBuildWithModel() {
    JobRow row = new JobRow();
    row.fullName = "pipeline-cache-resolvability-test/" + System.nanoTime();
    row.displayName = "orig";
    row.pipelineScript = "";
    row.configJson = "{}";
    row.enabled = true;
    long jobId = stores.jobs().insert(row);
    Build created =
        buildService.create(
            new NewBuildRequest(jobId, null, "test", "test", null, EMPTY_PIPELINE_JSON, null));
    return created.id();
  }
}
