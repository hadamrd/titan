package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import org.junit.jupiter.api.Test;

/**
 * Tests for the top-level {@code include:} directive (issue #1120).
 *
 * <p>Covers the contract:
 *
 * <ul>
 *   <li>local include inlines a sibling fragment whose stages prepend the main file's;
 *   <li>cross-repo include is delegated to {@link IncludeResolver#resolveRepo};
 *   <li>nested includes resolve depth-first;
 *   <li>cycles are detected and surface a clear actionable error (not a stack overflow);
 *   <li>depth cap fires before nesting runs unbounded;
 *   <li>a missing local include surfaces a {@link PipelineParseException} naming the path;
 *   <li>a non-list {@code include:} is a typed parse error;
 *   <li>a cross-repo include missing a required key surfaces a typed error;
 *   <li>parser-without-resolver rejects a present-and-non-empty include with a clear hint.
 * </ul>
 */
class IncludeProcessorTest {

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void localIncludeInlinesSiblingFragment() {
    String shared =
        "stages:\n"
            + "  - stage: lint\n"
            + "    steps:\n"
            + "      - sh: ./scripts/lint.sh\n"
            + "  - stage: test\n"
            + "    steps:\n"
            + "      - sh: ./scripts/test.sh\n";
    String main =
        "include:\n"
            + "  - shared/lint-test.yml\n"
            + "stages:\n"
            + "  - stage: deploy\n"
            + "    dependsOn: test\n"
            + "    steps:\n"
            + "      - sh: ./scripts/deploy.sh\n";

    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("shared/lint-test.yml", shared);
    PipelineModel model = TitanYamlParser.parse(main, r);

    // Stages must be: included first (lint, test) then the main file's (deploy).
    assertEquals(3, model.getStages().size());
    assertEquals("lint", model.getStages().get(0).getName());
    assertEquals("test", model.getStages().get(1).getName());
    assertEquals("deploy", model.getStages().get(2).getName());
  }

  @Test
  void multipleLocalIncludesAreConcatenatedInOrder() {
    String lint = "stages:\n  - stage: lint\n    steps:\n      - sh: lint\n";
    String test = "stages:\n  - stage: test\n    steps:\n      - sh: test\n";
    String main =
        "include:\n  - lint.yml\n  - test.yml\nstages:\n  - stage: build\n    steps:\n      - sh: build\n";

    InMemoryIncludeResolver r =
        new InMemoryIncludeResolver().put("lint.yml", lint).put("test.yml", test);
    PipelineModel model = TitanYamlParser.parse(main, r);

    assertEquals(3, model.getStages().size());
    assertEquals("lint", model.getStages().get(0).getName());
    assertEquals("test", model.getStages().get(1).getName());
    assertEquals("build", model.getStages().get(2).getName());
  }

  @Test
  void crossRepoIncludeIsDelegatedToResolver() {
    String fragment =
        "stages:\n  - stage: shared-lint\n    steps:\n      - sh: lint-from-other-repo\n";
    String main =
        "include:\n"
            + "  - { repo: 'https://example.com/ci.git', ref: 'v1', path: 'lint.yml' }\n"
            + "stages:\n  - stage: build\n    steps:\n      - sh: build\n";

    InMemoryIncludeResolver r =
        new InMemoryIncludeResolver().put("https://example.com/ci.git@v1:lint.yml", fragment);
    PipelineModel model = TitanYamlParser.parse(main, r);

    assertEquals(2, model.getStages().size());
    assertEquals("shared-lint", model.getStages().get(0).getName());
    assertEquals("build", model.getStages().get(1).getName());
  }

  @Test
  void includeFromTitanWrapperFormResolves() {
    String shared = "stages:\n  - stage: lint\n    steps:\n      - sh: lint\n";
    String main =
        "titan:\n"
            + "  include:\n"
            + "    - shared.yml\n"
            + "  stages:\n"
            + "    - stage: build\n"
            + "      steps:\n"
            + "        - sh: build\n";

    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("shared.yml", shared);
    PipelineModel model = TitanYamlParser.parse(main, r);

    assertEquals(2, model.getStages().size());
  }

  @Test
  void nestedIncludeResolvesDepthFirst() {
    String inner = "stages:\n  - stage: inner\n    steps:\n      - sh: inner\n";
    String middle =
        "include:\n  - inner.yml\nstages:\n  - stage: middle\n    steps:\n      - sh: middle\n";
    String main =
        "include:\n  - middle.yml\nstages:\n  - stage: outer\n    steps:\n      - sh: outer\n";
    InMemoryIncludeResolver r =
        new InMemoryIncludeResolver().put("inner.yml", inner).put("middle.yml", middle);

    PipelineModel model = TitanYamlParser.parse(main, r);
    // inner (depth-first from middle), then middle, then outer
    assertEquals(3, model.getStages().size());
    assertEquals("inner", model.getStages().get(0).getName());
    assertEquals("middle", model.getStages().get(1).getName());
    assertEquals("outer", model.getStages().get(2).getName());
  }

  @Test
  void mainFileScalarOverridesIncluded() {
    String shared = "agent: ubuntu-20\nstages:\n  - stage: lint\n    steps:\n      - sh: lint\n";
    String main =
        "include:\n  - shared.yml\nagent: ubuntu-22\nstages:\n  - stage: build\n    steps:\n      - sh: build\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("shared.yml", shared);
    PipelineModel model = TitanYamlParser.parse(main, r);

    assertEquals("ubuntu-22", model.getAgent(), "main file's agent must override included");
  }

  // ── adversarial / sad paths ──────────────────────────────────────────────

  @Test
  void cycleSurfacesActionableError() {
    String a = "include:\n  - b.yml\nstages:\n  - stage: a\n    steps:\n      - sh: a\n";
    String b = "include:\n  - a.yml\nstages:\n  - stage: b\n    steps:\n      - sh: b\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("a.yml", a).put("b.yml", b);
    String main = "include:\n  - a.yml\nstages:\n  - stage: main\n    steps:\n      - sh: main\n";
    // Main -> a.yml -> b.yml -> a.yml. The processor walks visited and catches the
    // second resolution of a.yml as a cycle, rather than recursing forever.
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main, r));
    assertTrue(
        ex.getMessage().contains("include cycle"),
        "expected 'include cycle' in error, got: " + ex.getMessage());
  }

  @Test
  void depthCapFires() {
    // Build a chain longer than the cap by aliasing each level to the next.
    InMemoryIncludeResolver r = new InMemoryIncludeResolver();
    int chain = IncludeProcessor.MAX_INCLUDE_DEPTH + 5;
    for (int i = 0; i < chain; i++) {
      String next = "f" + (i + 1) + ".yml";
      String body =
          "include:\n  - " + next + "\nstages:\n  - stage: s" + i + "\n    steps:\n      - sh: x\n";
      r.put("f" + i + ".yml", body);
    }
    // Terminal: no include
    r.put("f" + chain + ".yml", "stages:\n  - stage: end\n    steps:\n      - sh: x\n");

    String main = "include:\n  - f0.yml\nstages:\n  - stage: main\n    steps:\n      - sh: x\n";
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main, r));
    assertTrue(
        ex.getMessage().contains("nesting exceeds"),
        "expected depth-cap error, got: " + ex.getMessage());
  }

  @Test
  void missingLocalIncludeSurfacesClearError() {
    String main = "include:\n  - missing.yml\nstages:\n  - stage: x\n    steps:\n      - sh: x\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver();
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main, r));
    assertTrue(
        ex.getMessage().contains("missing.yml") || ex.getMessage().contains("no in-memory"),
        "expected the missing path to be named, got: " + ex.getMessage());
  }

  @Test
  void includeMustBeStringObjectOrList() {
    // #1128 accepts string OR object OR list; an integer (or any other shape) is rejected with
    // a typed parse error that names the accepted shapes.
    String main = "include: 42\nstages:\n  - stage: x\n    steps:\n      - sh: x\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver();
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main, r));
    assertTrue(
        ex.getMessage().contains("'include'"),
        "expected 'include' shape error, got: " + ex.getMessage());
  }

  @Test
  void crossRepoIncludeMissingKeyRejected() {
    // missing 'path'
    String main =
        "include:\n  - { repo: 'https://x', ref: 'v1' }\nstages:\n  - stage: x\n    steps:\n      - sh: x\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver();
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main, r));
    assertTrue(
        ex.getMessage().contains("missing required key 'path'"),
        "expected missing-required-key error, got: " + ex.getMessage());
  }

  @Test
  void crossRepoIncludeUnknownKeyRejected() {
    String main =
        "include:\n"
            + "  - { repo: 'https://x', ref: 'v1', path: 'lint.yml', oops: 'typo' }\n"
            + "stages:\n  - stage: x\n    steps:\n      - sh: x\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver();
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main, r));
    assertTrue(
        ex.getMessage().contains("unknown key 'oops'"),
        "expected unknown-key error, got: " + ex.getMessage());
  }

  @Test
  void parserWithoutResolverRejectsPresentInclude() {
    String main = "include:\n  - sibling.yml\nstages:\n  - stage: x\n    steps:\n      - sh: x\n";
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main));
    assertTrue(
        ex.getMessage().contains("'include:'") && ex.getMessage().contains("IncludeResolver"),
        "expected a hint pointing at parse(yaml, baseDir), got: " + ex.getMessage());
  }

  @Test
  void presentButNullIncludeIsNoOp() {
    String main = "include: ~\nstages:\n  - stage: x\n    steps:\n      - sh: x\n";
    // A null include is benign — no resolver call, behaves like absent.
    PipelineModel model = TitanYamlParser.parse(main);
    assertEquals(1, model.getStages().size());
  }

  @Test
  void invalidYamlInsideIncludedFileReportsSourceFile() {
    String shared = "stages: : : ::\n  not yaml at all\n";
    String main = "include:\n  - shared.yml\nstages:\n  - stage: x\n    steps:\n      - sh: x\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("shared.yml", shared);
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(main, r));
    assertTrue(
        ex.getMessage().contains("shared.yml") && ex.getMessage().contains("not valid YAML"),
        "expected source filename + parse hint, got: " + ex.getMessage());
  }

  @Test
  void inlinedFragmentIdenticalToInlinedEquivalent() {
    // The whole point of the feature: the model from the include form must equal the model from
    // a hand-inlined equivalent. We check stage IDs + step descriptor ids + step args.
    String shared = "stages:\n  - stage: lint\n    steps:\n      - sh: ./lint.sh\n";
    String includeForm =
        "include:\n  - shared.yml\nstages:\n  - stage: build\n    steps:\n      - sh: ./build.sh\n";
    String inlinedForm =
        "stages:\n"
            + "  - stage: lint\n    steps:\n      - sh: ./lint.sh\n"
            + "  - stage: build\n    steps:\n      - sh: ./build.sh\n";
    InMemoryIncludeResolver r = new InMemoryIncludeResolver().put("shared.yml", shared);
    PipelineModel viaInclude = TitanYamlParser.parse(includeForm, r);
    PipelineModel viaInline = TitanYamlParser.parse(inlinedForm);

    assertEquals(viaInline.getStages().size(), viaInclude.getStages().size());
    for (int i = 0; i < viaInline.getStages().size(); i++) {
      StageModel a = viaInline.getStages().get(i);
      StageModel b = viaInclude.getStages().get(i);
      assertEquals(a.getName(), b.getName(), "stage[" + i + "].name");
      assertEquals(a.getId(), b.getId(), "stage[" + i + "].id");
      assertEquals(a.getSteps().size(), b.getSteps().size(), "stage[" + i + "].steps.size");
      for (int j = 0; j < a.getSteps().size(); j++) {
        assertEquals(a.getSteps().get(j).getDescriptorId(), b.getSteps().get(j).getDescriptorId());
        assertEquals(a.getSteps().get(j).getArguments(), b.getSteps().get(j).getArguments());
      }
    }
    assertNotNull(viaInclude.getStages());
  }
}
