package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.orch.EnvResolver.EnvResolutionException;
import io.adaptiq.titan.flow.orch.EnvResolver.PlaintextLookup;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the env {@code secret:<id>} resolver (closes #1094).
 *
 * <p>Coverage:
 *
 * <ul>
 *   <li>happy path — literals pass through, secret refs are resolved against the lookup;
 *   <li>adversarial — missing secret, empty plaintext, lookup throwing — all surface as a typed
 *       {@link EnvResolutionException} (NEVER an NPE);
 *   <li>iteration order is preserved across plain + secret outputs (regression: a step that mixes
 *       literals with secrets sees its env in declaration order, both in payload and in the mask
 *       list);
 *   <li>empty input is a no-op (three empty collections).
 * </ul>
 *
 * <p>Tests use an in-memory {@link PlaintextLookup} backed by a {@link Map} — no DAO, no cipher, no
 * Postgres; this is a pure unit test of the splitter's logic + error mapping.
 */
class EnvResolverTest {

  /** A deterministic in-memory lookup — id → plaintext. */
  private static PlaintextLookup fixedLookup(Map<String, String> store) {
    return id -> Optional.ofNullable(store.get(id));
  }

  @Test
  void emptyInputYieldsEmptyOutputs() {
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of()));
    EnvResolver.Split out = resolver.split(Map.of());
    assertTrue(out.plain().isEmpty());
    assertTrue(out.secret().isEmpty());
    assertTrue(out.maskValues().isEmpty());
    assertFalse(out.hasSecrets());
  }

  @Test
  void literalValuesPassThroughUnchanged() {
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of()));
    Map<String, String> in = new LinkedHashMap<>();
    in.put("LOG_LEVEL", "info");
    in.put("REGISTRY", "registry.example.com");
    EnvResolver.Split out = resolver.split(in);
    assertEquals(in, out.plain());
    assertTrue(out.secret().isEmpty());
    assertTrue(out.maskValues().isEmpty());
  }

  @Test
  void bareSecretRefResolvesAgainstLookup() {
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of("gh-token", "ghp_TOPSECRET")));
    Map<String, String> in = Map.of("GH_TOKEN", "secret:gh-token");
    EnvResolver.Split out = resolver.split(in);
    assertTrue(out.plain().isEmpty(), "the literal must NOT appear in plain map");
    assertEquals("ghp_TOPSECRET", out.secret().get("GH_TOKEN"));
    assertEquals(1, out.maskValues().size());
    assertEquals("ghp_TOPSECRET", out.maskValues().get(0));
    assertTrue(out.hasSecrets());
  }

  @Test
  void scopedSecretRefPassesIdToLookupAsIs() {
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of("aws/dev-key", "AKIA-DEV")));
    EnvResolver.Split out = resolver.split(Map.of("AWS_KEY", "secret:aws/dev-key"));
    assertEquals("AKIA-DEV", out.secret().get("AWS_KEY"));
  }

  @Test
  void mixedLiteralAndSecretValuesArePartitioned() {
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of("gh-token", "ghp_SECRET")));
    // Use a LinkedHashMap to assert preserved iteration order.
    Map<String, String> in = new LinkedHashMap<>();
    in.put("LOG_LEVEL", "info");
    in.put("GH_TOKEN", "secret:gh-token");
    in.put("REGISTRY", "registry.example.com");
    EnvResolver.Split out = resolver.split(in);
    assertEquals(Map.of("LOG_LEVEL", "info", "REGISTRY", "registry.example.com"), out.plain());
    assertEquals(Map.of("GH_TOKEN", "ghp_SECRET"), out.secret());
    assertEquals(1, out.maskValues().size());
    assertEquals("ghp_SECRET", out.maskValues().get(0));
  }

  @Test
  void missingSecretFailsWithStructuredErrorNotNpe() {
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of()));
    EnvResolutionException e =
        assertThrows(
            EnvResolutionException.class,
            () -> resolver.split(Map.of("GH_TOKEN", "secret:gh-token")));
    // The message must name BOTH the env var and the credential id.
    assertTrue(e.getMessage().contains("GH_TOKEN"), e.getMessage());
    assertTrue(e.getMessage().contains("gh-token"), e.getMessage());
    assertTrue(
        e.getMessage().contains("not in the Titan credentials store"),
        "must explain why: " + e.getMessage());
  }

  @Test
  void emptyPlaintextFailsWithStructuredErrorNotPropagated() {
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of("gh-token", "")));
    EnvResolutionException e =
        assertThrows(
            EnvResolutionException.class,
            () -> resolver.split(Map.of("GH_TOKEN", "secret:gh-token")));
    assertTrue(e.getMessage().contains("GH_TOKEN"), e.getMessage());
    assertTrue(e.getMessage().contains("empty"), e.getMessage());
  }

  @Test
  void lookupThrowingRuntimeIsWrappedNotPropagated() {
    EnvResolver resolver =
        new EnvResolver(
            id -> {
              throw new IllegalStateException("vault is sealed");
            });
    EnvResolutionException e =
        assertThrows(
            EnvResolutionException.class,
            () -> resolver.split(Map.of("GH_TOKEN", "secret:gh-token")));
    assertTrue(e.getMessage().contains("GH_TOKEN"));
    assertTrue(e.getMessage().contains("vault is sealed"));
    assertEquals(IllegalStateException.class, e.getCause().getClass());
  }

  @Test
  void nullValueIsTreatedAsEmptyLiteral() {
    // Defensive — the parser rejects this, but the resolver must not NPE if a caller passes
    // a map with a null value (e.g. from an upstream merge edge case).
    Map<String, String> in = new LinkedHashMap<>();
    in.put("WEIRD", null);
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of()));
    EnvResolver.Split out = resolver.split(in);
    assertEquals("", out.plain().get("WEIRD"));
    assertTrue(out.secret().isEmpty());
  }

  @Test
  void multipleSecretsAccumulateMaskValuesInDeclarationOrder() {
    EnvResolver resolver = new EnvResolver(fixedLookup(Map.of("a", "AAA", "b", "BBB", "c", "CCC")));
    Map<String, String> in = new LinkedHashMap<>();
    in.put("FIRST", "secret:a");
    in.put("SECOND", "secret:b");
    in.put("THIRD", "secret:c");
    EnvResolver.Split out = resolver.split(in);
    assertEquals(3, out.maskValues().size());
    assertEquals("AAA", out.maskValues().get(0));
    assertEquals("BBB", out.maskValues().get(1));
    assertEquals("CCC", out.maskValues().get(2));
  }
}
