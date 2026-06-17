package io.adaptiq.titan.artifact;

import io.adaptiq.titan.flow.artifact.ArtifactStore;
import io.adaptiq.titan.flow.artifact.ArtifactStoreRegistry;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the controller-side {@link ArtifactStore} for serving the build-page artifact browser
 * (design/41 §8.4, 32E-3).
 *
 * <p>The controller reads a {@code titan.artifact} row's {@code storage} kind and asks here for a
 * store of that kind. Backend configuration comes from the controller's {@code TITAN_ARTIFACT_*}
 * environment — the server uses env exclusively, the same way the worker does.
 *
 * <p>Controller and worker <em>must</em> resolve an identically configured backend (the same
 * filesystem root) — env-only configuration is how that is guaranteed in the standalone product.
 *
 * <p>Stores are cached per kind — they are cheap, immutable handles. {@link #invalidate()} drops
 * the cache so a config change can take effect without a server restart.
 */
public final class ArtifactStores {

  private static final Logger LOGGER = Logger.getLogger(ArtifactStores.class.getName());

  private static final String STORE_KIND_VAR = "TITAN_ARTIFACT_STORE";
  private static final String CONFIG_PREFIX = "TITAN_ARTIFACT_";

  private static final Map<String, ArtifactStore> CACHE = new ConcurrentHashMap<>();

  private ArtifactStores() {}

  /**
   * The store for a given backend kind ({@code fs} / {@code s3} / …) — the value from a {@code
   * titan.artifact.storage} column.
   *
   * @throws IllegalArgumentException if no provider serves {@code kind}, or its configuration is
   *     missing — the caller (the download endpoint) turns this into a clear HTTP error
   */
  public static ArtifactStore forKind(String kind) {
    return CACHE.computeIfAbsent(kind, k -> ArtifactStoreRegistry.resolve(k, resolveConfig()));
  }

  /**
   * Drop the per-kind store cache — called when artifact-store config is changed. Each evicted
   * store is {@link ArtifactStore#close() closed} so its resources (an S3 client's HTTP connection
   * pool, for instance) are released rather than leaked on every reconfigure.
   */
  public static void invalidate() {
    for (Iterator<Map.Entry<String, ArtifactStore>> it = CACHE.entrySet().iterator();
        it.hasNext(); ) {
      ArtifactStore evicted = it.next().getValue();
      it.remove();
      try {
        evicted.close();
      } catch (IOException | RuntimeException e) {
        LOGGER.log(
            Level.WARNING,
            "[titan] failed to close evicted artifact store of kind '" + evicted.kind() + "'",
            e);
      }
    }
  }

  /** Backend config from the server's {@code TITAN_ARTIFACT_*} environment. */
  private static Map<String, String> resolveConfig() {
    Map<String, String> config = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
      String key = entry.getKey();
      if (key.startsWith(CONFIG_PREFIX) && !key.equals(STORE_KIND_VAR)) {
        config.put(
            key.substring(CONFIG_PREFIX.length()).toLowerCase(Locale.ROOT), entry.getValue());
      }
    }
    return config;
  }
}
