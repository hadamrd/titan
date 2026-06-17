package io.adaptiq.titan.worker.step;

import java.io.IOException;
import java.lang.System.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The controller-side step registry — the single assembly point that aggregates every {@link
 * StepHandlerProvider} on the classpath (design/32 §8, design/42 §4.2 / §4.6).
 *
 * <p>The worker already discovers {@link StepHandlerProvider}s via {@link ServiceLoader} at startup
 * to <em>execute</em> steps (see {@code StepHandlerDiscovery} in {@code titan-worker}). The
 * controller needs the same set, but for the <em>metadata</em>: descriptor ids, display names, help
 * text, param specs, schema fragments — what drives the editor palette, the assembled {@code
 * titan-pipeline.schema.json} extension fragment, and parser argument-validation hints.
 *
 * <p>This registry is the controller's view of that catalogue. It mirrors {@link
 * io.adaptiq.titan.flow.artifact.ArtifactStoreRegistry} and the {@code CredentialKeyProvider} /
 * {@code SecretsBackend} SPIs: pure {@link ServiceLoader}.
 *
 * <p><strong>Why a separate entry point from the worker's discovery.</strong> The worker discovers
 * providers under a per-jar classloader rooted at {@code TITAN_STEPS_DIR}; that has subtle
 * classloader rules (the parent's providers must not be re-enumerated — see design/42 §4.3 and the
 * fix in {@code StepHandlerDiscovery}). The controller only needs <em>metadata</em>: it loads
 * providers via the SPI's own classloader, exactly as {@link
 * io.adaptiq.titan.flow.artifact.ArtifactStoreRegistry} does — so built-in handlers shipped
 * alongside this jar are always found, and the controller has no opinion on per-jar isolation
 * (those jars only need to be on the controller classpath if their descriptors are wanted in the
 * editor; the worker still owns execution).
 */
public final class StepRegistry {

  private static final Logger LOGGER = System.getLogger(StepRegistry.class.getName());

  private StepRegistry() {}

  /**
   * Every {@link StepDescriptor} on the path — keyed by {@link StepDescriptor#descriptorId()},
   * preserving SPI discovery order. The map is unmodifiable.
   *
   * <p>Discovery is best-effort: a provider that throws on {@code handlers()} or yields a {@code
   * null} handler is logged and skipped, never propagated. The registry stays consistent.
   */
  public static Map<String, StepDescriptor> descriptors() {
    Map<String, StepDescriptor> out = new LinkedHashMap<>();
    StepHandlerContext context = neutralContext();
    for (StepHandlerProvider provider : load()) {
      List<StepHandler> handlers;
      try {
        handlers = provider.handlers(context);
      } catch (RuntimeException e) {
        LOGGER.log(
            Logger.Level.WARNING,
            () ->
                "step registry: provider '"
                    + safeDescribe(provider)
                    + "' threw on handlers() — skipping",
            e);
        continue;
      }
      if (handlers == null) {
        continue;
      }
      for (StepHandler handler : handlers) {
        if (handler == null) {
          continue;
        }
        StepDescriptor descriptor;
        try {
          descriptor = handler.descriptor();
        } catch (RuntimeException e) {
          LOGGER.log(
              Logger.Level.WARNING,
              () ->
                  "step registry: handler '"
                      + handler.getClass().getName()
                      + "' threw on descriptor() — skipping",
              e);
          continue;
        }
        if (descriptor == null) {
          continue;
        }
        // Two providers with the same descriptorId is a packaging bug — first one wins, the
        // collision is logged. The worker enforces uniqueness hard; the controller stays soft
        // (a duplicate-id browser palette is annoying, not corrupting).
        StepDescriptor existing = out.putIfAbsent(descriptor.descriptorId(), descriptor);
        if (existing != null) {
          final String dup = descriptor.descriptorId();
          LOGGER.log(
              Logger.Level.WARNING,
              () -> "step registry: duplicate descriptorId '" + dup + "' — keeping the first");
        }
      }
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * Resolve the descriptor for {@code descriptorId}, or {@link Optional#empty()} if no provider on
   * the path declares it.
   */
  public static Optional<StepDescriptor> find(String descriptorId) {
    Objects.requireNonNull(descriptorId, "descriptorId");
    return Optional.ofNullable(descriptors().get(descriptorId));
  }

  /**
   * Every {@link StepHandlerProvider} on the path — for the startup audit log (design/42 §4.3 rule
   * 5). Caller iterates and prints {@link StepHandlerProvider#describe()}.
   */
  public static List<StepHandlerProvider> providers() {
    java.util.ArrayList<StepHandlerProvider> out = new java.util.ArrayList<>();
    for (StepHandlerProvider provider : load()) {
      out.add(provider);
    }
    return Collections.unmodifiableList(out);
  }

  private static ServiceLoader<StepHandlerProvider> load() {
    // Load against the SPI's own classloader — mirrors ArtifactStoreRegistry. Ensures built-in
    // providers bundled with this jar are always discovered, regardless of the caller's
    // classloader topology.
    return ServiceLoader.load(
        StepHandlerProvider.class, StepHandlerProvider.class.getClassLoader());
  }

  /**
   * The neutral {@link StepHandlerContext} the registry hands every provider at descriptor-harvest
   * time. The controller has no library-cache root and no worker logger; descriptors are pure data
   * on the handler, so providers must not reach into the context to compute them. The temp dir is
   * created lazily and only if at least one provider is on the path.
   */
  private static StepHandlerContext neutralContext() {
    try {
      Path placeholder = Files.createTempDirectory("titan-step-registry-");
      placeholder.toFile().deleteOnExit();
      return new StepHandlerContext(placeholder, StepApi.VERSION, LOGGER);
    } catch (IOException e) {
      // Fall back to the JVM working directory — the registry must never crash on a controller
      // that cannot create a temp dir; descriptors don't read from this path.
      return new StepHandlerContext(Path.of("."), StepApi.VERSION, LOGGER);
    }
  }

  private static String safeDescribe(StepHandlerProvider provider) {
    try {
      String d = provider.describe();
      return d != null ? d : provider.getClass().getName();
    } catch (RuntimeException e) {
      return provider.getClass().getName();
    }
  }
}
