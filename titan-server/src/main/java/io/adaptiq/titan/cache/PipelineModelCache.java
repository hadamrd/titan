package io.adaptiq.titan.cache;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.quarkus.cache.CacheInvalidate;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;

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
 */
@ApplicationScoped
public class PipelineModelCache {

  /** Lenient on unknown properties so the persisted model survives forward model evolution. */
  private static final ObjectMapper JSON =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private final TitanStores stores;

  public PipelineModelCache(TitanStores stores) {
    this.stores = stores;
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
      return JSON.readValue(build.pipelineModelJson, PipelineModel.class);
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
   * Static convenience for non-CDI call sites (the engine helper classes such as {@code
   * BuildAbortService}, {@code TitanOrchestrator} and {@code QueueProcessor} are constructed
   * directly, not via CDI). Looks up the bean via {@link CDI#current()} and delegates.
   *
   * <p>Falls back to a no-op when no CDI container is running (plain JUnit unit tests that exercise
   * the engine helpers outside {@code @QuarkusTest}).
   */
  public static void invalidateIfActive(long buildId) {
    try {
      CDI.current().select(PipelineModelCache.class).get().invalidate(buildId);
    } catch (RuntimeException ignored) {
      // CDI not running (raw JUnit) or bean not resolvable — the cache only exists when
      // titan-server is up, so dropping the call is safe.
    }
  }

  /**
   * Static accessor for the {@link TitanOrchestrator#advance()} hot path — uses the CDI bean (and
   * its {@code @CacheResult} interceptor) when a Quarkus container is running, otherwise falls
   * through to a direct fresh load against {@code daos}.
   *
   * <p>The fallback is the plain-JUnit safety net for the engine unit tests that exercise the
   * orchestrator without booting Quarkus.
   */
  @NonNull
  public static PipelineModel loadOrFresh(@NonNull TitanStores daos, long buildId) {
    try {
      return CDI.current().select(PipelineModelCache.class).get().load(buildId);
    } catch (RuntimeException cdiUnavailable) {
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
}
