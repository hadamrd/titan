package io.adaptiq.titan.worker.step;

import io.adaptiq.titan.worker.step.manifest.ContainerManifestStepHandler;
import io.adaptiq.titan.worker.step.manifest.StepManifest;
import io.adaptiq.titan.worker.step.manifest.StepManifestLoader;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the worker's {@link StepHandlerRegistry} by {@code ServiceLoader} discovery (design/42
 * §4.3).
 *
 * <p>Discovery sources, in order:
 *
 * <ol>
 *   <li>the worker classpath — finds {@link SocleStepHandlerProvider} (the socle dogfoods the SPI);
 *   <li>every {@code *.jar} in {@code TITAN_STEPS_DIR} (env var, default {@code ./steps/}), each in
 *       its own child {@link URLClassLoader} parented to the worker core (design/42 §5.3) so two
 *       jars bundling different library versions do not collide — Tier-2 steps;
 *   <li>every {@code *.titanstep.yaml} in the same {@code TITAN_STEPS_DIR} — Tier-1 declarative
 *       container steps (design/42 §4.8): each manifest is parsed and registered as a {@link
 *       ContainerManifestStepHandler}. A manifest is pure data — no jar, no classloader.
 * </ol>
 *
 * <p>Per provider the rules of design/42 §4.3 / §4.4 apply: a version-gated reject (provider newer
 * than {@link StepApi#VERSION}, or below the floor), a throwing provider skipped with a WARNING, a
 * duplicate {@code descriptorId} a hard fail-fast error, and an audit line logged for each.
 */
public final class StepHandlerDiscovery {

  private static final Logger LOG = LoggerFactory.getLogger(StepHandlerDiscovery.class);

  /** Environment variable naming the directory of drop-in Tier-2 step jars (design/42 §4.3). */
  public static final String STEPS_DIR_ENV = "TITAN_STEPS_DIR";

  /**
   * Reserved vendor namespace. A third-party step jar that ships a class under this prefix is
   * REFUSED at discovery time — a hard boot error. CONSTITUTION-grade guarantee (closes #474): no
   * outside jar may shadow Titan internals by placing a class in {@code io.adaptiq.titan.*} and
   * winning a child-loader resolution race.
   */
  public static final String RESERVED_NAMESPACE = "io.adaptiq.titan.";

  /** Default {@code TITAN_STEPS_DIR} when the env var is unset (design/42 §4.3). */
  public static final String DEFAULT_STEPS_DIR = "./steps/";

  /**
   * The lowest provider {@code apiVersion()} the worker still accepts (design/42 §4.4). A provider
   * below this floor is rejected. A simple floor of 1 for now.
   */
  public static final int API_VERSION_FLOOR = 1;

  private StepHandlerDiscovery() {
    // static-only
  }

  /**
   * Discover all providers and assemble the registry.
   *
   * @param context the narrow worker state handed to every provider
   * @return a populated {@link StepHandlerRegistry}
   * @throws IllegalStateException on a duplicate {@code descriptorId} — fail-fast (§4.3 rule 3)
   */
  public static StepHandlerRegistry discover(StepHandlerContext context) {
    return discover(context, resolveStepsDir());
  }

  /**
   * Discover all providers and assemble the registry, scanning {@code stepsDir} for external jars
   * and manifests. Package-private — the entry point a test drives at an explicit {@code @TempDir},
   * since a JVM cannot mutate its own {@code TITAN_STEPS_DIR} environment.
   *
   * @param context the narrow worker state handed to every provider
   * @param stepsDir the directory of drop-in jars / manifests (may be missing — then no external)
   * @return a populated {@link StepHandlerRegistry}
   * @throws IllegalStateException on a duplicate {@code descriptorId} — fail-fast (§4.3 rule 3)
   */
  static StepHandlerRegistry discover(StepHandlerContext context, Path stepsDir) {
    StepHandlerRegistry registry = new StepHandlerRegistry(List.of());

    // (1) the worker classpath — the socle. No classloader filter: every provider visible on
    // the worker classpath (the socle's SocleStepHandlerProvider) is genuinely ours.
    ServiceLoader<StepHandlerProvider> classpath =
        ServiceLoader.load(StepHandlerProvider.class, StepHandlerDiscovery.class.getClassLoader());
    loadFrom(classpath, "classpath", null, context, registry);

    // (2) every jar in TITAN_STEPS_DIR, each in its own child classloader.
    //
    // A child URLClassLoader delegates to its parent (the worker core), so
    // ServiceLoader.load(..., child) enumerates the parent's META-INF/services entries TOO —
    // it would re-discover the socle's SocleStepHandlerProvider (already loaded in step 1) and
    // crash on its duplicate descriptor ids. So the per-jar scan is filtered to providers
    // actually DEFINED BY that jar's child loader: a provider whose class loaded through the
    // parent was already registered by the classpath scan and is skipped here (§4.3 / §5.3).
    List<Path> jars = externalJars(stepsDir);
    java.util.Set<String> beforeIds = registry.descriptorIds();
    for (Path jar : jars) {
      // CONSTITUTION-grade gate (#474): a third-party jar that places a class in
      // io.adaptiq.titan.* would shadow worker internals through its child loader. Refuse
      // to load it — a HARD boot error, surfaced exactly like a duplicate descriptor id.
      assertNoReservedNamespaceShadow(jar);
      URLClassLoader child = childLoader(jar);
      if (child == null) {
        continue;
      }
      ServiceLoader<StepHandlerProvider> external =
          ServiceLoader.load(StepHandlerProvider.class, child);
      loadFrom(external, jar.toAbsolutePath().toString(), child, context, registry);
    }
    // (3) every *.titanstep.yaml in TITAN_STEPS_DIR — Tier-1 declarative container steps.
    for (Path file : externalFiles(stepsDir, StepManifestLoader::isManifest, "step manifests")) {
      registerManifest(file, registry);
    }

    // Per-directory summary (#474): one INFO line listing every external step id contributed
    // by jars + manifests from this steps dir, regardless of how many providers / jars / yaml
    // files produced them. Computed as the delta against the classpath-only baseline.
    java.util.Set<String> afterIds = registry.descriptorIds();
    List<String> externalStepIds = new ArrayList<>(afterIds);
    externalStepIds.removeAll(beforeIds);
    java.util.Collections.sort(externalStepIds);
    LOG.info(
        "loaded {} third-party step handler(s) from {} steps: {}",
        externalStepIds.size(),
        stepsDir.toAbsolutePath(),
        externalStepIds);
    return registry;
  }

  /** Resolve {@code TITAN_STEPS_DIR} from the environment, defaulting to {@code ./steps/}. */
  private static Path resolveStepsDir() {
    String configured = System.getenv(STEPS_DIR_ENV);
    return Path.of(configured == null || configured.isBlank() ? DEFAULT_STEPS_DIR : configured);
  }

  /** List a directory's {@code *.jar} files (sorted, stable order). */
  private static List<Path> externalJars(Path dir) {
    return externalFiles(
        dir, p -> p.getFileName().toString().toLowerCase().endsWith(".jar"), "step jars");
  }

  /**
   * List the regular files in {@code dir} matching {@code accept}, sorted for a stable,
   * reproducible order. A missing directory yields an empty list.
   */
  private static List<Path> externalFiles(
      Path dir, java.util.function.Predicate<Path> accept, String what) {
    if (!Files.isDirectory(dir)) {
      LOG.info(
          "step discovery: {} ({}) is not a directory — no external {}",
          STEPS_DIR_ENV,
          dir.toAbsolutePath(),
          what);
      return List.of();
    }
    try (Stream<Path> entries = Files.list(dir)) {
      return entries
          .filter(accept)
          .filter(Files::isRegularFile)
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      LOG.warn(
          "step discovery: cannot list {} ({}) — skipping external {}: {}",
          STEPS_DIR_ENV,
          dir.toAbsolutePath(),
          what,
          e.toString());
      return List.of();
    }
  }

  /**
   * Parse one {@code *.titanstep.yaml} manifest and register it as a {@link
   * ContainerManifestStepHandler}.
   *
   * <p>The 42-P posture is mirrored exactly: a malformed manifest is skipped with a WARNING and
   * discovery continues (§4.3 rule 4 — one bad file cannot crash the worker); a manifest whose
   * {@code step} id collides with an already-registered handler is the same hard, fail-fast
   * duplicate-id error as §4.3 rule 3 (the {@code IllegalStateException} from {@link
   * StepHandlerRegistry#register} propagates); and an audit line is logged per manifest (file
   * origin, step id) mirroring §4.3 rule 5.
   */
  private static void registerManifest(Path file, StepHandlerRegistry registry) {
    StepManifest manifest;
    try {
      manifest = StepManifestLoader.load(file);
    } catch (RuntimeException e) {
      // A bad manifest must not crash discovery (§4.3 rule 4 — same posture as a bad jar).
      LOG.warn(
          "step discovery: skipping malformed step manifest {} — {}",
          file.toAbsolutePath(),
          e.toString());
      return;
    }
    // A duplicate descriptorId is a HARD error (§4.3 rule 3) — let register() throw.
    registry.register(new ContainerManifestStepHandler(manifest));
    LOG.info(
        "step discovery: loaded Tier-1 container step manifest origin={} step={} image={}",
        file.toAbsolutePath(),
        manifest.step(),
        manifest.image());
  }

  /**
   * Refuse to load a third-party jar that places any {@code .class} entry under the reserved {@code
   * io.adaptiq.titan.*} namespace (#474). A child {@link URLClassLoader} resolves a class in that
   * namespace from the jar itself when its name does not already exist on the parent loader — a
   * straightforward shadow of worker internals. The CTO decision is to refuse the jar at boot
   * rather than rely on classloader ordering: a worker that started despite the shadow is a worker
   * whose internals are now ambiguous. We walk the jar entries with {@link JarFile} and fail on the
   * first matching entry, with a message naming the jar path, the offending class, and the reserved
   * namespace — operators must rename their package and redeploy.
   *
   * @throws IllegalStateException if any class entry's package starts with {@link
   *     #RESERVED_NAMESPACE}
   */
  static void assertNoReservedNamespaceShadow(Path jar) {
    try (JarFile jf = new JarFile(jar.toFile())) {
      Enumeration<JarEntry> entries = jf.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (entry.isDirectory() || !name.endsWith(".class")) {
          continue;
        }
        // "io/adaptiq/titan/worker/fake/FakeShadow.class" →
        // "io.adaptiq.titan.worker.fake.FakeShadow"
        String className = name.substring(0, name.length() - ".class".length()).replace('/', '.');
        if (className.startsWith(RESERVED_NAMESPACE)) {
          throw new IllegalStateException(
              "step discovery: REFUSED step jar "
                  + jar.toAbsolutePath()
                  + " — it bundles class "
                  + className
                  + " in the reserved Titan namespace '"
                  + RESERVED_NAMESPACE
                  + "*'. Third-party step jars must not shadow worker internals; rename the "
                  + "offending package and redeploy.");
        }
      }
    } catch (IOException e) {
      // A jar we cannot open cannot be vetted; let childLoader() handle the open failure
      // uniformly (it logs a WARNING and skips the jar — §4.3 rule 4).
      LOG.warn(
          "step discovery: cannot inspect step jar {} for reserved-namespace shadowing: {}",
          jar,
          e.toString());
    }
  }

  /**
   * A child {@link URLClassLoader} for one external jar, parented to the worker core (§5.3). The
   * jar sees {@code titan-step-api}, the JDK, and its own bundled deps — not worker internals.
   */
  private static URLClassLoader childLoader(Path jar) {
    try {
      URL url = jar.toUri().toURL();
      return new URLClassLoader(
          "titan-step-jar:" + jar.getFileName(),
          new URL[] {url},
          StepHandlerDiscovery.class.getClassLoader());
    } catch (Exception e) {
      // A bad jar must not crash the worker (§4.3 rule 4).
      LOG.warn("step discovery: cannot open step jar {} — skipping: {}", jar, e.toString());
      return null;
    }
  }

  /**
   * Iterate a {@link ServiceLoader} defensively: version-gate, skip throwers, register, audit.
   * Iteration uses the lazy {@code Iterator} so a {@link ServiceConfigurationError} from one broken
   * provider does not abort the rest.
   *
   * @param definedBy when non-{@code null}, accept ONLY providers whose class was defined by this
   *     classloader. A child {@link URLClassLoader} delegates to the worker core, so {@code
   *     ServiceLoader} would otherwise re-discover the parent's providers (the socle's {@code
   *     SocleStepHandlerProvider}) through every external jar's loader and crash on a spurious
   *     duplicate descriptor id. {@code null} means no filter — used for the classpath scan, whose
   *     providers are all genuinely ours.
   */
  private static void loadFrom(
      ServiceLoader<StepHandlerProvider> loader,
      String origin,
      ClassLoader definedBy,
      StepHandlerContext context,
      StepHandlerRegistry registry) {

    var it = loader.iterator();
    while (true) {
      StepHandlerProvider provider;
      try {
        if (!it.hasNext()) {
          break;
        }
        provider = it.next();
      } catch (ServiceConfigurationError | RuntimeException e) {
        // Instantiation of one provider failed — skip it, keep going (§4.3 rule 4).
        LOG.warn(
            "step discovery: a provider from {} failed to load — skipping: {}",
            origin,
            e.toString());
        continue;
      }
      // Per-jar scan: skip a provider that the child loader merely inherited from the parent
      // (the socle provider, or anything already on the worker classpath). It was already
      // registered by the classpath scan — re-registering it is the duplicate-id crash.
      if (definedBy != null && provider.getClass().getClassLoader() != definedBy) {
        LOG.debug(
            "step discovery: skipping provider {} visible through {} but defined by "
                + "the parent loader — already registered by the classpath scan",
            provider.getClass().getName(),
            origin);
        continue;
      }
      registerProvider(provider, origin, context, registry);
    }
  }

  /** Version-gate, invoke {@code handlers()}, register each, and emit the audit line. */
  private static void registerProvider(
      StepHandlerProvider provider,
      String origin,
      StepHandlerContext context,
      StepHandlerRegistry registry) {

    String providerClass = provider.getClass().getName();
    int apiVersion;
    try {
      apiVersion = provider.apiVersion();
    } catch (RuntimeException e) {
      LOG.warn(
          "step discovery: provider {} (origin {}) threw from apiVersion() — skipping: {}",
          providerClass,
          origin,
          e.toString());
      return;
    }

    // Version gate (§4.4).
    if (apiVersion > StepApi.VERSION) {
      LOG.warn(
          "step discovery: REJECTED provider {} (origin {}) — built against step-SPI v{}, "
              + "this worker is v{}; its steps will be absent",
          providerClass,
          origin,
          apiVersion,
          StepApi.VERSION);
      return;
    }
    if (apiVersion < API_VERSION_FLOOR) {
      LOG.warn(
          "step discovery: REJECTED provider {} (origin {}) — step-SPI v{} is below the "
              + "compatibility floor v{}; its steps will be absent",
          providerClass,
          origin,
          apiVersion,
          API_VERSION_FLOOR);
      return;
    }
    if (apiVersion < StepApi.VERSION) {
      LOG.info(
          "step discovery: provider {} (origin {}) built against older step-SPI v{} "
              + "(worker v{}) — within floor, loading",
          providerClass,
          origin,
          apiVersion,
          StepApi.VERSION);
    }

    // Invoke handlers() defensively (§4.3 rule 4).
    String describe;
    List<StepHandler> handlers;
    try {
      describe = provider.describe();
      handlers = provider.handlers(context);
    } catch (RuntimeException e) {
      LOG.warn(
          "step discovery: provider {} (origin {}) threw during discovery — skipping: {}",
          providerClass,
          origin,
          e.toString());
      return;
    }
    if (handlers == null) {
      LOG.warn(
          "step discovery: provider {} (origin {}) returned null handlers — skipping",
          providerClass,
          origin);
      return;
    }

    // Register each handler. A duplicate descriptorId is a HARD error (§4.3 rule 3) — let the
    // IllegalStateException from register() propagate; the worker refuses to start.
    List<String> contributed = new ArrayList<>();
    for (StepHandler handler : handlers) {
      registry.register(handler);
      contributed.add(handler.descriptorId());
    }

    // Audit line per provider (§4.3 rule 5).
    LOG.info(
        "step discovery: loaded provider class={} describe=\"{}\" origin={} apiVersion={} "
            + "steps={}",
        providerClass,
        describe,
        origin,
        apiVersion,
        contributed);
  }
}
