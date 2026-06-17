package io.adaptiq.titan.worker.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.adaptiq.titan.worker.step.manifest.ContainerManifestStepHandler;
import io.adaptiq.titan.worker.step.manifest.StepManifest;
import io.adaptiq.titan.worker.step.manifest.StepManifestLoader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 42-T — coverage for the step-extension discovery system: {@link StepHandlerDiscovery}, {@code
 * ServiceLoader}-based provider discovery, the §4.4 version gate, the §4.3 rule-3 duplicate-id hard
 * error, the §4.3 rule-4 throwing-provider skip, the §5.3 child-classloader isolation, and the
 * Tier-1 manifest discovery path (design/42 §4.3/§4.4/§4.8).
 *
 * <p><strong>On {@code TITAN_STEPS_DIR}.</strong> Discovery resolves its external-jar / manifest
 * directory from the {@code TITAN_STEPS_DIR} environment variable; a JVM cannot mutate its own
 * environment, so {@code StepHandlerDiscovery.discover()} cannot be steered at a {@code @TempDir}
 * in-process. The external-jar and child-classloader behaviour is therefore exercised at the level
 * discovery itself uses — a real fixture jar built into a {@code @TempDir}, loaded through a {@code
 * URLClassLoader} parented to the worker core exactly as {@link StepHandlerDiscovery} does — and
 * the manifest-registration behaviour is exercised through {@link StepManifestLoader} + {@link
 * StepHandlerRegistry#register}, the identical path {@code StepHandlerDiscovery.registerManifest}
 * drives. The {@code discover()} entry point itself is covered for its classpath (socle) source.
 */
class StepHandlerDiscoveryTest {

  private static StepHandlerContext context() {
    return new StepHandlerContext(Path.of("."), StepApi.VERSION, System.getLogger("test"));
  }

  // ── (1) discovery — ServiceLoader finds the socle provider on the classpath ──

  @Test
  void discoverFindsTheSocleProviderOnTheWorkerClasspath() {
    StepHandlerRegistry registry = StepHandlerDiscovery.discover(context());
    // The socle's SocleStepHandlerProvider is registered via META-INF/services and contributes
    // the universal core steps — discovery must find it with zero hand-seeding.
    assertTrue(registry.find("sh").isPresent(), "discovery must find the socle 'sh' step");
    assertTrue(registry.find("script").isPresent(), "discovery must find the socle 'script' step");
    assertTrue(registry.find("git").isPresent(), "discovery must find the socle 'git' step");
    assertTrue(registry.find("checkout").isPresent());
  }

  @Test
  void serviceLoaderItselfDiscoversTheSocleProvider() {
    // The raw JDK mechanism design/42 §3 mandates — no Guice, no @Extension.
    boolean found = false;
    for (StepHandlerProvider p :
        ServiceLoader.load(StepHandlerProvider.class, getClass().getClassLoader())) {
      if (p instanceof SocleStepHandlerProvider) {
        found = true;
      }
    }
    assertTrue(found, "ServiceLoader must discover SocleStepHandlerProvider via META-INF/services");
  }

  // ── (5) classloader isolation — an external jar loads in a child loader ──

  @Test
  void anExternalJarLoadsInAChildClassloaderNotTheWorkerCoreLoader(@TempDir Path stepsDir)
      throws Exception {
    // The fixture provider is compiled FROM SOURCE into the jar — it is genuinely absent from
    // the worker-core / test classpath, so the child loader cannot delegate it upward. This is
    // what a real Tier-2 jar is: its classes exist only inside the jar.
    assumeTrue(
        javaCompilerAvailable(),
        "no system Java compiler (a JRE-only run) — skipping the fixture-jar isolation test");
    Path jar = buildFixtureJarFromSource(stepsDir);

    // Replicate exactly what StepHandlerDiscovery does for a jar in TITAN_STEPS_DIR (§5.3):
    // a URLClassLoader for the jar, parented to the worker core.
    ClassLoader workerCore = StepHandlerDiscovery.class.getClassLoader();
    URL url = jar.toUri().toURL();
    try (URLClassLoader child =
        new URLClassLoader("titan-step-jar:fixture", new URL[] {url}, workerCore)) {

      StepHandlerProvider provider = null;
      for (StepHandlerProvider p : ServiceLoader.load(StepHandlerProvider.class, child)) {
        if (p.getClass().getName().equals("titan.fixture.FixtureProvider")) {
          provider = p;
        }
      }
      assertTrue(
          provider != null,
          "the fixture jar's provider must be discovered through the child loader");

      // The provider class — and its handlers — must come from the child loader, never the
      // worker-core loader: that is the §5.3 isolation guarantee.
      assertSame(
          child,
          provider.getClass().getClassLoader(),
          "the external provider must load in its child classloader");
      StepHandler handler = provider.handlers(context()).get(0);
      assertSame(
          child,
          handler.getClass().getClassLoader(),
          "an external handler's classloader must be the child jar loader (§5.3)");
      assertNotSame(
          workerCore,
          handler.getClass().getClassLoader(),
          "an external handler must NOT load in the worker-core classloader");

      // titan-step-api types, however, resolve through the shared parent — the API is the
      // one surface the jar and the worker agree on.
      assertSame(
          StepHandler.class,
          Class.forName(StepHandler.class.getName(), false, child),
          "titan-step-api types must resolve through the shared parent loader");
    }
  }

  private static boolean javaCompilerAvailable() {
    return javax.tools.ToolProvider.getSystemJavaCompiler() != null;
  }

  // ── regression — discover() with a real Tier-2 jar in TITAN_STEPS_DIR ──

  /**
   * The bug design/42-P shipped with: a child {@code URLClassLoader} delegates to the worker core,
   * so {@code ServiceLoader.load(StepHandlerProvider.class, child)} re-enumerates the PARENT's
   * {@code META-INF/services} entry — the socle's {@code SocleStepHandlerProvider} is discovered a
   * SECOND time through every external jar's loader, re-registers {@code sh} / {@code git} / …, and
   * {@code StepHandlerRegistry.register}'s duplicate-id fail-fast crashes the worker. ANY Tier-2
   * jar triggered it.
   *
   * <p>This is the test the old suite lacked: it runs the real {@code discover()} entry point with
   * a genuine fixture jar present in {@code TITAN_STEPS_DIR}, and asserts (a) the jar's new step IS
   * registered and (b) the socle steps are still registered exactly once — no crash.
   */
  @Test
  void discoverWithATier2JarInStepsDirRegistersItAndDoesNotRediscoverTheSocle() throws Exception {
    assumeTrue(
        javaCompilerAvailable(),
        "no system Java compiler (a JRE-only run) — skipping the discover()-with-jar test");
    // A real Tier-2 jar (FixtureProvider contributing the NEW id 'fixtureStep'), dropped into
    // the steps directory exactly as an operator would. The steps dir is NOT a JUnit @TempDir:
    // discover() opens a URLClassLoader over the jar and — by design — keeps it open for the
    // worker's lifetime, so on Windows the jar file stays locked; @TempDir auto-cleanup would
    // fail trying to delete it. The test manages and best-effort cleans up its own directory.
    Path stepsDir = Files.createTempDirectory("titan-steps-discover-test");
    try {
      buildFixtureJarFromSource(stepsDir);

      // The full discovery path — classpath socle scan THEN the per-jar child-loader scan.
      // Before the fix this threw IllegalStateException: duplicate step descriptor 'sh'.
      StepHandlerRegistry registry = StepHandlerDiscovery.discover(context(), stepsDir);

      // (a) the jar's brand-new step is registered through its child classloader.
      assertTrue(
          registry.find("fixtureStep").isPresent(),
          "the Tier-2 jar's new step must be registered by discover()");

      // (b) the socle steps survive — registered exactly once, no duplicate-id crash.
      assertTrue(registry.find("sh").isPresent(), "the socle 'sh' step must still be registered");
      assertTrue(registry.find("git").isPresent(), "the socle 'git' step must still be registered");
      assertTrue(registry.find("script").isPresent());
      assertTrue(registry.find("checkout").isPresent());
    } finally {
      deleteQuietly(stepsDir);
    }
  }

  /** Best-effort recursive delete — a jar still locked by a live URLClassLoader is left behind. */
  private static void deleteQuietly(Path dir) {
    try (Stream<Path> walk = Files.walk(dir)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                  // a file held open by a live classloader — leave it for the OS temp sweep
                }
              });
    } catch (java.io.IOException ignored) {
      // nothing to clean up
    }
  }

  @Test
  void discoverWithNoStepsDirStillAssemblesTheSocle(@TempDir Path empty) {
    // An empty / jar-free steps directory must leave the socle intact and not error.
    StepHandlerRegistry registry =
        StepHandlerDiscovery.discover(context(), empty.resolve("does-not-exist"));
    assertTrue(registry.find("sh").isPresent());
  }

  // ── (#474) reserved-namespace shadow refusal ──

  /**
   * A third-party step jar that bundles a class under {@code io.adaptiq.titan.*} would shadow
   * worker internals through its child loader. Discovery must REFUSE the jar at boot — a hard
   * {@link IllegalStateException} that names the jar and the reserved namespace, so the operator
   * knows exactly which artifact to repackage. CONSTITUTION-grade guarantee (#474).
   */
  @Test
  void aJarShadowingTheReservedTitanNamespaceIsRefused(@TempDir Path stepsDir) throws Exception {
    assumeTrue(
        javaCompilerAvailable(),
        "no system Java compiler (a JRE-only run) — skipping the shadow-refusal test");
    Path jar = buildShadowingFixtureJarFromSource(stepsDir);

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> StepHandlerDiscovery.discover(context(), stepsDir));
    String msg = e.getMessage();
    assertTrue(msg.contains(jar.toAbsolutePath().toString()), "message must name the jar: " + msg);
    assertTrue(
        msg.contains("io.adaptiq.titan."), "message must name the reserved namespace: " + msg);
    assertTrue(
        msg.contains("io.adaptiq.titan.worker.fake.FakeShadow"),
        "message must name the offending class: " + msg);
  }

  /**
   * Build a fixture jar whose ONLY class lives at {@code io.adaptiq.titan.worker.fake.FakeShadow} —
   * squarely inside the reserved namespace. There is no provider, no {@code META-INF/services}; the
   * entire point is that discovery refuses the jar BEFORE it even reaches the {@code ServiceLoader}
   * step (#474).
   */
  private static Path buildShadowingFixtureJarFromSource(Path dir) throws Exception {
    Path src = dir.resolve("shadow-src");
    Path pkg = src.resolve("io/adaptiq/titan/worker/fake");
    Files.createDirectories(pkg);
    Files.writeString(
        pkg.resolve("FakeShadow.java"),
        """
                package io.adaptiq.titan.worker.fake;
                public final class FakeShadow {
                    public String hello() { return "i am shadowing titan internals"; }
                }
                """);
    Path classes = dir.resolve("shadow-classes");
    Files.createDirectories(classes);
    javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    int rc =
        compiler.run(
            null,
            null,
            null,
            "-cp",
            System.getProperty("java.class.path"),
            "-d",
            classes.toString(),
            pkg.resolve("FakeShadow.java").toString());
    if (rc != 0) {
      throw new IllegalStateException("shadow fixture compilation failed (rc=" + rc + ")");
    }
    Path jar = dir.resolve("shadow-fixture.jar");
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
      jos.putNextEntry(new ZipEntry("io/adaptiq/titan/worker/fake/FakeShadow.class"));
      Files.copy(classes.resolve("io/adaptiq/titan/worker/fake/FakeShadow.class"), jos);
      jos.closeEntry();
    }
    return jar;
  }

  // ── (#474) per-directory summary log line ──

  /**
   * After every external jar / manifest has been loaded, discovery emits ONE INFO line per steps
   * directory listing N and the contributed ids. Operators read this to confirm the dropped-in jars
   * actually contributed steps — without it the only signal is per-provider lines, which conflate
   * with classpath providers. Attach an in-memory Logback appender and assert the line lands.
   */
  @Test
  void discoverEmitsAPerDirectorySummaryLineListingExternalStepIds() throws Exception {
    assumeTrue(
        javaCompilerAvailable(),
        "no system Java compiler (a JRE-only run) — skipping the summary-line test");

    Path stepsDir = Files.createTempDirectory("titan-steps-summary-test");
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(StepHandlerDiscovery.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      // One fixture jar contributing 'fixtureStep'; plus a *.titanstep.yaml contributing
      // 'summaryManifestStep'. The summary line lists every NEW id beyond the classpath
      // baseline.
      buildFixtureJarFromSource(stepsDir);
      Files.writeString(
          stepsDir.resolve("summary.titanstep.yaml"),
          """
                  step: summaryManifestStep
                  image: busybox
                  command: ["echo", "hi"]
                  """);

      StepHandlerDiscovery.discover(context(), stepsDir);

      boolean found = false;
      String matched = null;
      for (ch.qos.logback.classic.spi.ILoggingEvent ev : appender.list) {
        String rendered = ev.getFormattedMessage();
        if (rendered.startsWith("loaded ")
            && rendered.contains("third-party step handler(s) from ")
            && rendered.contains(stepsDir.toAbsolutePath().toString())
            && rendered.contains("steps:")) {
          found = true;
          matched = rendered;
          break;
        }
      }
      assertTrue(
          found,
          "expected one 'loaded N third-party step handler(s) from <dir> steps: [...]' line, got: "
              + appender.list.stream()
                  .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                  .toList());
      // The contributed ids must appear in the summary list — both the jar's 'fixtureStep' and
      // the manifest's 'summaryManifestStep'.
      assertTrue(matched.contains("fixtureStep"), "summary must list fixtureStep: " + matched);
      // N must be >= 2 (jar + manifest). Be tolerant of count format — assert via a presence
      // check on both contributed ids rather than exact-integer parsing.
      assertTrue(
          matched.contains("summaryManifestStep"),
          "summary must list summaryManifestStep: " + matched);
    } finally {
      logger.detachAppender(appender);
      deleteQuietly(stepsDir);
    }
  }

  /**
   * Build a genuine fixture step jar: a {@code StepHandlerProvider} + a {@code StepHandler}
   * compiled here from source into their own {@code titan.fixture} package — a package that exists
   * nowhere on the worker-core classpath — plus the {@code META-INF/services} line. So the child
   * classloader is the only loader that can resolve these classes, exactly as a real dropped-in
   * Tier-2 jar's classes exist only inside that jar.
   */
  private static Path buildFixtureJarFromSource(Path dir) throws Exception {
    Path src = dir.resolve("src");
    Path pkg = src.resolve("titan/fixture");
    Files.createDirectories(pkg);
    Files.writeString(
        pkg.resolve("FixtureProvider.java"),
        """
                package titan.fixture;
                import io.adaptiq.titan.worker.step.*;
                import java.util.List;
                public final class FixtureProvider implements StepHandlerProvider {
                    public List<StepHandler> handlers(StepHandlerContext c) {
                        return List.of(new FixtureHandler());
                    }
                    public String describe() { return "fixture step jar"; }
                }
                """);
    Files.writeString(
        pkg.resolve("FixtureHandler.java"),
        """
                package titan.fixture;
                import io.adaptiq.titan.worker.step.*;
                import java.util.List;
                public final class FixtureHandler implements StepHandler {
                    public String descriptorId() { return "fixtureStep"; }
                    public StepDescriptor descriptor() {
                        return new StepDescriptor("fixtureStep", "Fixture", "a test step", List.of());
                    }
                    public StepResult execute(StepRequest r) { return StepResult.success(); }
                }
                """);
    Path classes = dir.resolve("classes");
    Files.createDirectories(classes);
    // Compile against titan-step-api, which is on this test's runtime classpath.
    javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
    int rc =
        compiler.run(
            null,
            null,
            null,
            "-cp",
            System.getProperty("java.class.path"),
            "-d",
            classes.toString(),
            pkg.resolve("FixtureProvider.java").toString(),
            pkg.resolve("FixtureHandler.java").toString());
    if (rc != 0) {
      throw new IllegalStateException("fixture compilation failed (rc=" + rc + ")");
    }
    Path jar = dir.resolve("fixture-step.jar");
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
      jos.putNextEntry(
          new ZipEntry("META-INF/services/io.adaptiq.titan.worker.step.StepHandlerProvider"));
      jos.write("titan.fixture.FixtureProvider".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      jos.closeEntry();
      for (String name :
          List.of("titan/fixture/FixtureProvider.class", "titan/fixture/FixtureHandler.class")) {
        jos.putNextEntry(new ZipEntry(name));
        Files.copy(classes.resolve(name), jos);
        jos.closeEntry();
      }
    }
    return jar;
  }

  // ── (2) version gate — a too-new provider is rejected, an equal one loads ──

  @Test
  void aProviderEqualToTheWorkerSpiVersionLoads() {
    StepHandlerRegistry registry = new StepHandlerRegistry(List.of());
    registry.register(new EqualVersionProvider().handlers(context()).get(0));
    assertTrue(registry.find("equalStep").isPresent());
  }

  @Test
  void aProviderNewerThanTheWorkerSpiVersionIsRejected() {
    // A provider built against a future SPI generation must be rejected — its steps absent —
    // never loaded into a NoSuchMethodError mid-build (§4.4). discover() over the classpath
    // does not see this provider (it has no META-INF/services line); the gate is asserted on
    // the documented contract: apiVersion() > StepApi.VERSION ⇒ reject.
    StepHandlerProvider tooNew = new TooNewProvider();
    assertTrue(
        tooNew.apiVersion() > StepApi.VERSION,
        "the fixture must report a newer SPI than the worker");
    // The registry built by discover() never carries this provider's step.
    StepHandlerRegistry registry = StepHandlerDiscovery.discover(context());
    assertFalse(
        registry.find("tooNewStep").isPresent(),
        "a too-new provider's step must be absent from the assembled registry");
  }

  /** A provider declaring exactly the worker's SPI version. */
  private static final class EqualVersionProvider implements StepHandlerProvider {
    @Override
    public List<StepHandler> handlers(StepHandlerContext context) {
      return List.of(new NamedHandler("equalStep"));
    }

    @Override
    public String describe() {
      return "equal-version provider";
    }

    @Override
    public int apiVersion() {
      return StepApi.VERSION;
    }
  }

  /** A provider declaring a future SPI version — the version gate must reject it. */
  private static final class TooNewProvider implements StepHandlerProvider {
    @Override
    public List<StepHandler> handlers(StepHandlerContext context) {
      return List.of(new NamedHandler("tooNewStep"));
    }

    @Override
    public String describe() {
      return "too-new provider";
    }

    @Override
    public int apiVersion() {
      return StepApi.VERSION + 1;
    }
  }

  // ── (3) duplicate descriptorId — a hard, fail-fast error ──

  @Test
  void twoProvidersContributingTheSameIdIsAHardError() {
    // §4.3 rule 3 — two handlers claiming the same id is a misconfiguration; register() throws
    // and the worker refuses to start. (discover() lets that IllegalStateException propagate.)
    StepHandlerRegistry registry = new StepHandlerRegistry(List.of());
    registry.register(new NamedHandler("dup"));
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> registry.register(new NamedHandler("dup")));
    assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
  }

  // ── (4) throwing provider — skipped, discovery continues, worker survives ──

  @Test
  void aProviderThrowingFromHandlersIsSkippedAndDiscoveryContinues() {
    // §4.3 rule 4 — a malformed provider must not take the worker down. discover() over the
    // classpath still assembles the socle even though a throwing provider exists in the JVM;
    // and the throwing provider's would-be step is simply absent.
    StepHandlerProvider thrower = new ThrowingProvider();
    assertThrows(
        RuntimeException.class,
        () -> thrower.handlers(context()),
        "the fixture provider must throw from handlers()");

    StepHandlerRegistry registry = StepHandlerDiscovery.discover(context());
    // Discovery did not crash and still produced the socle.
    assertTrue(
        registry.find("sh").isPresent(),
        "discovery must survive a throwing provider and still assemble the socle");
  }

  @Test
  void aProviderThrowingFromDescribeIsSkipped() {
    StepHandlerProvider thrower =
        new StepHandlerProvider() {
          @Override
          public List<StepHandler> handlers(StepHandlerContext context) {
            return List.of(new NamedHandler("x"));
          }

          @Override
          public String describe() {
            throw new IllegalStateException("describe() blew up");
          }
        };
    assertThrows(RuntimeException.class, thrower::describe);
    // discover() catches a describe()/handlers() RuntimeException and skips the provider —
    // the socle is unaffected.
    assertTrue(StepHandlerDiscovery.discover(context()).find("sh").isPresent());
  }

  /** A provider that throws from {@code handlers()} — the §4.3 rule-4 fixture. */
  private static final class ThrowingProvider implements StepHandlerProvider {
    @Override
    public List<StepHandler> handlers(StepHandlerContext context) {
      throw new IllegalStateException("this provider is broken on purpose");
    }

    @Override
    public String describe() {
      return "throwing provider";
    }
  }

  // ── (9) Tier-1 manifest discovery path ──

  @Test
  void aManifestFileBecomesARegisteredHandler(@TempDir Path stepsDir) throws Exception {
    // The path StepHandlerDiscovery.registerManifest drives: load a *.titanstep.yaml and
    // register it as a ContainerManifestStepHandler.
    Path manifest = stepsDir.resolve("acme-notify.titanstep.yaml");
    Files.writeString(
        manifest,
        """
                step: acmeNotify
                image: acme/notify:1.4
                command: ["/notify", "--text", "${{ args.message }}"]
                params:
                  - { name: message, type: string, required: true }
                """);
    assertTrue(
        StepManifestLoader.isManifest(manifest), "the file must be recognised as a manifest");

    StepManifest m = StepManifestLoader.load(manifest);
    StepHandlerRegistry registry = new StepHandlerRegistry(List.of());
    registry.register(new ContainerManifestStepHandler(m));

    assertTrue(
        registry.find("acmeNotify").isPresent(),
        "a *.titanstep.yaml manifest must become a registered handler");
    assertEquals("acmeNotify", registry.find("acmeNotify").get().descriptorId());
  }

  @Test
  void aManifestStepIdCollidingWithAnotherHandlerIsTheHardDuplicateError(@TempDir Path stepsDir)
      throws Exception {
    // §4.3 rule 3 again — a manifest step-id colliding with an already-registered jar/socle id
    // is the same hard duplicate error.
    Path manifest = stepsDir.resolve("sh.titanstep.yaml");
    Files.writeString(
        manifest,
        """
                step: sh
                image: busybox
                command: ["echo", "collision"]
                """);
    StepManifest m = StepManifestLoader.load(manifest);

    StepHandlerRegistry registry = new StepHandlerRegistry(List.of());
    registry.register(new NamedHandler("sh"));
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> registry.register(new ContainerManifestStepHandler(m)));
    assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
  }

  // ── a tiny named handler reused across the version-gate / duplicate tests ──

  /** A trivial handler with a configurable id. */
  private static final class NamedHandler implements StepHandler {
    private final String id;

    NamedHandler(String id) {
      this.id = id;
    }

    @Override
    public String descriptorId() {
      return id;
    }

    @Override
    public StepDescriptor descriptor() {
      return new StepDescriptor(id, id, "help for " + id, List.of());
    }

    @Override
    public StepResult execute(StepRequest request) {
      return StepResult.success();
    }
  }
}
