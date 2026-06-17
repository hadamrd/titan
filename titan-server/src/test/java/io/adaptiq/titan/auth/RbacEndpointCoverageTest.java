package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Deny-by-default guard for the API surface (#1174). Reflectively scans every JAX-RS resource
 * method that carries a mutating verb ({@code @POST/@PUT/@PATCH/@DELETE}) under {@code
 * io.adaptiq.titan.api.**} and fails the build if any such method is reachable without a
 * {@code @RequiresRole} gate (method- or class-level) AND is not in the documented {@link #EXEMPT}
 * allowlist.
 *
 * <p>This is the meta-test the epic asked for: a NEW ungated mutating endpoint shipped without a
 * scoped role floor turns this RED, forcing the author to either gate it or add a justified
 * exemption. The guard is unit-tier (no Quarkus, no DB) — it loads the compiled classes off the
 * code-source directory and reflects their annotations.
 *
 * <p>Adversarial proof the guard can actually fail: {@link #guardFlagsAnUngatedMutator()} feeds the
 * same scan a throwaway un-annotated {@code @POST} fixture and asserts it IS flagged — a meta-test
 * that cannot go red is worthless (testing manifesto: "a test is valuable only if it could fail").
 */
class RbacEndpointCoverageTest {

  /**
   * The single documented exemption allowlist. Key shape is {@code SimpleClassName.methodName} —
   * the same {@code endpoint} label the {@link RequiresRoleInterceptor} stamps onto the {@code
   * rbac_audit} row. Every entry carries a one-line justification.
   *
   * <p>All current exemptions are webhook ingress endpoints: they authenticate by an <strong>HMAC
   * signature over the request body</strong> (GitHub {@code X-Hub-Signature-256}, GitLab {@code
   * X-Gitlab-Token}), NOT by a user bearer token. There is no user identity / scope to gate on, so
   * a {@code @RequiresRole} user-scope check would be semantically wrong (it would 403 every real
   * webhook delivery). See issue #1174 "Out of scope".
   */
  static final Set<String> EXEMPT =
      Set.of(
          // GitHub App webhook — HMAC X-Hub-Signature-256 over the body, no user token.
          "GithubAppWebhookApi.receive",
          // GitHub trigger webhook — HMAC X-Hub-Signature-256, no user token.
          "GithubWebhookApi.receive",
          // GitLab webhook — shared-secret X-Gitlab-Token header, no user token.
          "GitlabWebhookApi.receive",
          // Bitbucket webhook — HMAC X-Hub-Signature (sha256), constant-time compare, no user
          // token.
          "BitbucketWebhookApi.receive",
          // Pulsar webhook — HMAC X-Pulsar-Signature-256, constant-time compare, no user token.
          "PulsarWebhookApi.receive");

  private static final List<Class<? extends Annotation>> MUTATING_VERBS =
      List.of(POST.class, PUT.class, PATCH.class, DELETE.class);

  @Test
  void everyMutatingEndpointIsGatedOrExempt() {
    List<Class<?>> resources = loadApiClasses();
    assertFalse(
        resources.isEmpty(),
        "classpath scan found ZERO api classes — the scan is broken, not the surface clean");

    List<String> violations = findUngatedMutators(resources, EXEMPT);

    assertTrue(
        violations.isEmpty(),
        () ->
            "These mutating endpoints lack @RequiresRole and are not in the EXEMPT allowlist "
                + "(deny-by-default violation, #1174). Gate them with @RequiresRole or add a "
                + "justified exemption:\n  - "
                + String.join("\n  - ", violations));
  }

  /**
   * Adversarial: the scan logic MUST flag a mutating method with no gate. We run it against a
   * private fixture carrying an un-annotated {@code @POST} and assert it surfaces — proving the
   * guard above is load-bearing and not a tautology.
   */
  @Test
  void guardFlagsAnUngatedMutator() {
    List<String> violations = findUngatedMutators(List.of(UngatedFixture.class), Set.of());
    assertTrue(
        violations.contains("UngatedFixture.mutateWithoutGate"),
        () -> "scan failed to flag an obviously-ungated @POST; got " + violations);
  }

  /** And the exemption path works: the same fixture, when exempted, is NOT flagged. */
  @Test
  void exemptionSuppressesAKnownGap() {
    List<String> violations =
        findUngatedMutators(
            List.of(UngatedFixture.class), Set.of("UngatedFixture.mutateWithoutGate"));
    assertTrue(
        violations.isEmpty(), () -> "exempted method should not be flagged; got " + violations);
  }

  /** And a class-level @RequiresRole counts as a gate (no per-method annotation required). */
  @Test
  void classLevelGateCounts() {
    List<String> violations = findUngatedMutators(List.of(ClassGatedFixture.class), Set.of());
    assertTrue(
        violations.isEmpty(), () -> "class-level @RequiresRole should gate; got " + violations);
  }

  // ── scan core ──────────────────────────────────────────────────────────────

  /**
   * Pure scan: for every declared method on every class, if it carries a mutating verb and neither
   * the method nor its declaring class carries {@code @RequiresRole} and it is not exempt, record
   * {@code SimpleClassName.methodName}.
   */
  static List<String> findUngatedMutators(List<Class<?>> classes, Set<String> exempt) {
    List<String> out = new ArrayList<>();
    for (Class<?> c : classes) {
      Method[] methods;
      try {
        methods = c.getDeclaredMethods();
      } catch (NoClassDefFoundError e) {
        // A class whose deps can't link under the test classpath — not a resource we gate.
        continue;
      }
      boolean classGated = c.isAnnotationPresent(RequiresRole.class);
      for (Method m : methods) {
        if (!hasMutatingVerb(m)) {
          continue;
        }
        String key = c.getSimpleName() + "." + m.getName();
        if (exempt.contains(key)) {
          continue;
        }
        if (classGated || m.isAnnotationPresent(RequiresRole.class)) {
          continue;
        }
        out.add(key);
      }
    }
    return out;
  }

  private static boolean hasMutatingVerb(Method m) {
    for (Class<? extends Annotation> verb : MUTATING_VERBS) {
      if (m.isAnnotationPresent(verb)) {
        return true;
      }
    }
    return false;
  }

  // ── classpath discovery ─────────────────────────────────────────────────────

  /** Load every class under {@code io.adaptiq.titan.api} (recursively) off the code-source dir. */
  private static List<Class<?>> loadApiClasses() {
    Path root = codeSourceRoot();
    Path apiDir = root.resolve("io/adaptiq/titan/api");
    if (!Files.isDirectory(apiDir)) {
      throw new IllegalStateException("api package dir not found under code source: " + apiDir);
    }
    try (Stream<Path> walk = Files.walk(apiDir)) {
      List<String> fqcns =
          walk.filter(p -> p.toString().endsWith(".class"))
              .map(p -> toFqcn(root, p))
              .filter(n -> !n.endsWith("package-info") && !n.endsWith("module-info"))
              .collect(Collectors.toList());
      List<Class<?>> classes = new ArrayList<>(fqcns.size());
      ClassLoader cl = RbacEndpointCoverageTest.class.getClassLoader();
      for (String fqcn : fqcns) {
        try {
          // initialize=false: we only reflect annotations, never run static init.
          classes.add(Class.forName(fqcn, false, cl));
        } catch (LinkageError | ClassNotFoundException e) {
          // Genuine linkage failure only (synthetic artifact, missing optional dep) — not a
          // gateable resource. Narrow on purpose: a broader catch (Throwable / Error) would
          // silently drop a real @POST endpoint whose class fails to define, defeating the
          // deny-by-default scan with no signal.
        }
      }
      return classes;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path codeSourceRoot() {
    try {
      // The code source of THIS test class is .../build/classes/java/test; the production classes
      // sit alongside at .../build/classes/java/main. Resolve that sibling so we scan the gated
      // production resources, not the test tree.
      URI testRoot =
          RbacEndpointCoverageTest.class
              .getProtectionDomain()
              .getCodeSource()
              .getLocation()
              .toURI();
      Path testPath = Path.of(testRoot);
      Path mainPath = testPath.getParent().resolve("main");
      if (Files.isDirectory(mainPath.resolve("io/adaptiq/titan/api"))) {
        return mainPath;
      }
      // Fallback: some build layouts merge trees — scan the test root itself.
      return testPath;
    } catch (URISyntaxException e) {
      throw new IllegalStateException("cannot resolve code-source location", e);
    }
  }

  private static String toFqcn(Path root, Path classFile) {
    Path rel = root.relativize(classFile);
    String s = rel.toString().replace(rel.getFileSystem().getSeparator(), ".");
    return s.substring(0, s.length() - ".class".length());
  }

  // ── fixtures (adversarial proof the guard fires) ────────────────────────────

  /** Throwaway: a mutating endpoint with NO gate — the guard must flag it. */
  static final class UngatedFixture {
    @POST
    public void mutateWithoutGate() {
      // no @RequiresRole on purpose
    }
  }

  /** Throwaway: gate declared at the class level — must count as gated. */
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  static final class ClassGatedFixture {
    @POST
    public void mutate() {
      // gated via the class-level annotation
    }
  }
}
