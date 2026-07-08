package io.adaptiq.titan.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.quarkus.arc.Unremovable;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheResult;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import java.util.concurrent.atomic.AtomicLong;
import org.jboss.logging.Logger;

/**
 * Caffeine-backed cache for the parsed {@link PipelineModel} of a build.
 *
 * <p>Cache: {@code pipeline-model}.
 *
 * <p>Key: {@code buildId}.
 *
 * <p>TTL: 30 minutes (expire-after-write) / maximum-size=500 — see {@code application.properties}.
 *
 * <p>Invalidation triggers:
 *
 * <ul>
 *   <li>Build transitions to a terminal status (SUCCESS / FAILED / ABORTED / UNSTABLE) — wired at
 *       every engine-side terminal-write site via {@link #invalidate(long)}.
 *   <li>Build row deletion via {@link io.adaptiq.titan.build.BuildService#delete(long)}
 *       (annotation-driven on {@code BuildServiceImpl#delete}).
 *   <li>Build row update via {@link io.adaptiq.titan.build.BuildService#update} — annotation-driven
 *       on {@code BuildServiceImpl#update}.
 * </ul>
 *
 * <p><strong>Rationale.</strong> Every orchestrator {@code advance()} pass re-fetched {@code
 * pipeline_model_json} and re-deserialised it through Jackson — pure CPU + bytes the row contents
 * do not actually change after BAKE writes them. The model row is immutable from the moment the
 * build is baked until the row is removed; the TTL is the safety net, the invalidation hooks are
 * the correctness contract.
 *
 * <p><strong>ARC removability (#37).</strong> No production code {@code @Inject}s this bean — all
 * engine call sites reach it through the static {@link #loadOrFresh} / {@link #invalidateIfActive}
 * programmatic lookups. Without {@link Unremovable}, Quarkus ARC drops the bean at build time as
 * "unused", every lookup fails with a "programmatic lookup problem detected" WARN, and the cache is
 * silently never active. {@code @Unremovable} is the declaration; the {@link #onStart} observer is
 * a second, independent removal guard (beans with observer methods are never removed) and emits the
 * single boot-time INFO proving the cache is operative.
 */
@ApplicationScoped
@Unremovable
public class PipelineModelCache {

  private static final Logger LOG = Logger.getLogger(PipelineModelCache.class);

  /** Lenient on unknown properties so the persisted model survives forward model evolution. */
  private static final ObjectMapper JSON =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final TitanStores stores;

  /**
   * Number of actual DB-fetch + Jackson-parse executions performed by {@link #load(long)}. A cache
   * hit does NOT increment this (the {@code @CacheResult} interceptor short-circuits the body), so
   * {@code requests - parseCount() = hits}. Exposed so tests / operators can observe that the cache
   * is genuinely serving hits rather than silently re-parsing on every call (#37).
   */
  private final AtomicLong parseCount = new AtomicLong();

  public PipelineModelCache(TitanStores stores) {
    this.stores = stores;
  }

  /**
   * Boot-time proof-of-life: one INFO line stating the cache bean is wired and operative. Also
   * makes the bean structurally unremovable (observer methods exclude a bean from ARC's unused-bean
   * removal) independently of the {@code @Unremovable} annotation.
   */
  void onStart(@Observes StartupEvent event) {
    LOG.info(
        "[titan] pipeline-model cache active (caffeine 'pipeline-model', see"
            + " application.properties for TTL/size)");
  }

  /** Number of real (non-cached) load-and-parse executions since boot. */
  public long parseCount() {
    return parseCount.get();
  }

  /**
   * Cached load — fetch the build row and deserialise {@code pipeline_model_json} into a {@link
   * PipelineModel}. The result is cached under the {@code pipeline-model} cache by {@code buildId};
   * subsequent calls within the TTL skip the DB hit and the Jackson parse.
   *
   * @throws IllegalStateException if the build is unknown or has no synthesised model.
   */
  @CacheResult(cacheName = "pipeline-model")
  @NonNull
  public PipelineModel load(long buildId) {
    BuildRow build =
        stores
            .builds()
            .findById(buildId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "PipelineModelCache.load: build not found: " + buildId));
    if (build.pipelineModelJson == null || build.pipelineModelJson.isBlank()) {
      throw new IllegalStateException(
          "PipelineModelCache.load: build " + buildId + " has no synthesised pipeline model");
    }
    try {
      PipelineModel model = JSON.readValue(build.pipelineModelJson, PipelineModel.class);
      long parses = parseCount.incrementAndGet();
      LOG.debugf(
          "[titan] pipeline-model cache miss — parsed model for build %d (parse #%d)",
          buildId, parses);
      return model;
    } catch (Exception e) {
      throw new IllegalStateException(
          "PipelineModelCache.load: cannot deserialise model for build " + buildId, e);
    }
  }

  /**
   * Drop the cached model for {@code buildId}. Idempotent — a miss is a no-op.
   *
   * <p>Engine-side terminal-write code paths call this explicitly (see {@link
   * #invalidateIfActive}); the API-side write paths get this via the {@code @CacheInvalidate}
   * annotations on {@code BuildServiceImpl}.
   */
  @CacheInvalidate(cacheName = "pipeline-model")
  public void invalidate(long buildId) {
    // Body intentionally empty — Quarkus' interceptor performs the invalidation.
  }

  /**
   * Resolve the {@link PipelineModelCache} bean, distinguishing the two failure modes that the old
   * blanket {@code catch (RuntimeException)} conflated (#37):
   *
   * <ul>
   *   <li><strong>No CDI container running</strong> (plain JUnit unit tests exercising the engine
   *       helpers outside {@code @QuarkusTest}) — legitimate; returns {@code null} so callers use
   *       their uncached fallback.
   *   <li><strong>Container running but bean unresolvable</strong> (e.g. ARC removed it as unused)
   *       — a wiring bug that used to silently disable caching in production. Now fails loud: one
   *       ERROR with context, then {@link IllegalStateException}. Silent cache-disable is
   *       impossible.
   * </ul>
   */
  @Nullable
  private static PipelineModelCache beanOrNull() {
    CDI<Object> cdi;
    try {
      cdi = CDI.current();
    } catch (RuntimeException noContainer) {
      // No CDI container (raw JUnit) — the cache only exists when titan-server is up.
      return null;
    }
    Instance<PipelineModelCache> instance = cdi.select(PipelineModelCache.class);
    if (!instance.isResolvable()) {
      String msg =
          "[titan] PipelineModelCache bean is NOT resolvable although a CDI container is running."
              + " ARC most likely removed it as unused — check the @Unremovable annotation and the"
              + " StartupEvent observer on PipelineModelCache (#37). Refusing to silently disable"
              + " the pipeline-model cache.";
      LOG.error(msg);
      throw new IllegalStateException(msg);
    }
    return instance.get();
  }

  /**
   * Static convenience for non-CDI call sites (the engine helper classes such as {@code
   * BuildAbortService}, {@code TitanOrchestrator} and {@code QueueProcessor} are constructed
   * directly, not via CDI). Looks up the bean via {@link CDI#current()} and delegates.
   *
   * <p>No-op when no CDI container is running (plain JUnit); throws if a container is running but
   * the bean is unresolvable — see {@link #beanOrNull()}.
   */
  public static void invalidateIfActive(long buildId) {
    PipelineModelCache bean = beanOrNull();
    if (bean != null) {
      bean.invalidate(buildId);
    }
  }

  /**
   * Static accessor for the {@link TitanOrchestrator#advance()} hot path — uses the CDI bean (and
   * its {@code @CacheResult} interceptor) when a Quarkus container is running, otherwise falls
   * through to a direct fresh load against {@code daos}.
   *
   * <p>The fallback fires only when no container is running (plain-JUnit engine unit tests); a
   * running container with an unresolvable bean throws — see {@link #beanOrNull()}.
   */
  @NonNull
  public static PipelineModel loadOrFresh(@NonNull TitanStores daos, long buildId) {
    PipelineModelCache bean = beanOrNull();
    if (bean != null) {
      return bean.load(buildId);
    }
    // CDI not running — load + parse directly. Same logic as load(), without caching.
    BuildRow build =
        daos.builds()
            .findById(buildId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "PipelineModelCache.loadOrFresh: build not found: " + buildId));
    if (build.pipelineModelJson == null || build.pipelineModelJson.isBlank()) {
      throw new IllegalStateException(
          "PipelineModelCache.loadOrFresh: build "
              + buildId
              + " has no synthesised pipeline model");
    }
    try {
      return JSON.readValue(build.pipelineModelJson, PipelineModel.class);
    } catch (Exception e) {
      throw new IllegalStateException(
          "PipelineModelCache.loadOrFresh: cannot deserialise model for build " + buildId, e);
    }
  }
}
