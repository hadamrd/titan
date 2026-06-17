package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import org.junit.jupiter.api.Test;

/**
 * Issue #1128 — locked merge precedence for pipeline includes.
 *
 * <p>The contract (high → low precedence): {@code local file > last include in list > … > first
 * include in list}. Concretely:
 *
 * <ul>
 *   <li>{@code stages.<id>} declared locally fully <strong>replaces</strong> an included {@code
 *       stages.<id>} of the same id — no per-step merge inside a stage.
 *   <li>{@code env}, {@code libraries} maps are shallow-merged; local key wins on collision.
 *   <li>Top-level scalars: local wins outright.
 * </ul>
 *
 * <p>These tests pin the behavior independently of the underlying parser plumbing — they speak the
 * customer's mental model of "the file I'm editing is in charge".
 */
class IncludeMergeTest {

  // ── string form (#1128) ──────────────────────────────────────────────────

  @Test
  void includeAcceptsBareStringForm() {
    String shared = "stages:\n  - stage: lint\n    steps:\n      - sh: lint\n";
    String main = "include: shared.yml\nstages:\n  - stage: build\n    steps:\n      - sh: build\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("shared.yml", shared);
    PipelineModel model = TitanYamlParser.parse(main, r);
    assertEquals(2, model.getStages().size());
    assertEquals("lint", model.getStages().get(0).getName());
    assertEquals("build", model.getStages().get(1).getName());
  }

  // ── stage override (local wins by stage id) ──────────────────────────────

  @Test
  void localStageReplacesIncludedStageOfSameId() {
    // Included `test` runs the shared script; local `test` overrides with a longer suite.
    // Whole-stage replace — local's steps win, no per-step merging.
    String shared =
        "stages:\n"
            + "  - stage: lint\n    steps:\n      - sh: ./lint.sh\n"
            + "  - stage: test\n    steps:\n      - sh: ./test-quick.sh\n";
    String main =
        "include:\n  - shared.yml\n"
            + "stages:\n"
            + "  - stage: test\n    steps:\n      - sh: ./test-full.sh\n      - sh: ./coverage.sh\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("shared.yml", shared);

    PipelineModel model = TitanYamlParser.parse(main, r);

    // Only 2 stages — shared.lint kept, shared.test replaced by local.test, no duplicate.
    assertEquals(2, model.getStages().size(), "duplicate stage 'test' must be deduped");
    StageModel lint = byName(model, "lint");
    assertNotNull(lint, "shared 'lint' stage must survive — it has no local override");

    StageModel test = byName(model, "test");
    assertNotNull(test);
    // Local stage's 2 steps replace the included stage's 1 step — no merging.
    assertEquals(
        2,
        test.getSteps().size(),
        "local 'test' fully overrides included; no per-step merge inside a stage");
  }

  @Test
  void laterIncludeReplacesEarlierIncludeOfSameStageId() {
    String first = "stages:\n  - stage: deploy\n    steps:\n      - sh: deploy-v1.sh\n";
    String second = "stages:\n  - stage: deploy\n    steps:\n      - sh: deploy-v2.sh\n";
    String main =
        "include:\n  - first.yml\n  - second.yml\n"
            + "stages:\n  - stage: build\n    steps:\n      - sh: build\n";
    InMemoryIncludeResolver r =
        new InMemoryIncludeResolver().put("first.yml", first).put("second.yml", second);

    PipelineModel model = TitanYamlParser.parse(main, r);

    assertEquals(2, model.getStages().size());
    StageModel deploy = byName(model, "deploy");
    assertNotNull(deploy);
    // The LAST include wins on collision (#1128's locked precedence).
    assertTrue(
        firstShellOf(deploy).contains("deploy-v2.sh"),
        "last include must replace earlier same-id stage; got: " + firstShellOf(deploy));
  }

  // ── env shallow-merge — local key wins on collision ──────────────────────

  @Test
  void envIsShallowMergedAndLocalKeyWins() {
    String shared =
        "env:\n  NODE_ENV: production\n  CACHE_BUST: '1'\n"
            + "stages:\n  - stage: x\n    steps:\n      - sh: x\n";
    String main =
        "include:\n  - shared.yml\n"
            + "env:\n  NODE_ENV: staging\n  DEBUG: 'true'\n"
            + "stages:\n  - stage: y\n    steps:\n      - sh: y\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("shared.yml", shared);

    PipelineModel model = TitanYamlParser.parse(main, r);

    assertEquals("staging", model.getEnv().get("NODE_ENV"), "local env key must win on collision");
    assertEquals("1", model.getEnv().get("CACHE_BUST"), "included-only env keys must survive");
    assertEquals("true", model.getEnv().get("DEBUG"), "local-only env keys must be added");
  }

  // ── adversarial / sad paths (#1128) ──────────────────────────────────────

  @Test
  void absolutePathIsRejectedByDefaultResolver() {
    // The IncludeResolver contract rejects absolute paths client-side. We verify the contract
    // by exercising LocalRelativeIncludeResolver.validateForm via the static method.
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                IncludeResolver.LocalRelativeIncludeResolver.validateForm(
                    "/etc/passwd", "test context"));
    assertTrue(
        ex.getMessage().contains("absolute"),
        "expected an 'absolute path' rejection, got: " + ex.getMessage());
  }

  @Test
  void schemePrefixedPathIsRejected() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                IncludeResolver.LocalRelativeIncludeResolver.validateForm(
                    "https://example.com/x.yml", "test context"));
    assertTrue(
        ex.getMessage().contains("scheme") || ex.getMessage().contains("local-relative"),
        "expected scheme rejection, got: " + ex.getMessage());
  }

  @Test
  void includedYamlWithListAtRootIsRejectedCleanly() {
    // A YAML that parses fine but is not a pipeline-fragment shape (a top-level list) must
    // surface a clean, located parse error — never an NPE or a confusing downstream type error.
    String junk = "- this\n- is\n- a list\n";
    String main = "include:\n  - junk.yml\nstages:\n  - stage: x\n    steps:\n      - sh: x\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("junk.yml", junk);
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main, r));
    assertTrue(
        ex.getMessage().contains("must be a YAML object"),
        "expected 'must be a YAML object' error, got: " + ex.getMessage());
  }

  // ── helpers ──

  private static StageModel byName(PipelineModel m, String name) {
    return m.getStages().stream().filter(s -> name.equals(s.getName())).findFirst().orElse(null);
  }

  private static String firstShellOf(StageModel stage) {
    // The first step's `script` / `sh` argument — we use this to distinguish included-vs-local.
    Object args = stage.getSteps().get(0).getArguments();
    return String.valueOf(args);
  }
}
