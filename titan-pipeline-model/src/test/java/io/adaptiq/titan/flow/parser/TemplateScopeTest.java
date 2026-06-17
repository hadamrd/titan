package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests {@code use:} parsing and template-step inlining (design/56).
 *
 * <p>{@code TemplateScope} is stage-structural: it reads a local template file at parse time,
 * validates {@code with:} against the template's {@code params:} block, substitutes {@code
 * ${param.<name>}} occurrences and inlines the resulting step list onto the stage. These tests
 * cover the happy path, every error case enumerated in design/56 §3, and the v1 nesting / matrix /
 * each exclusions.
 *
 * <p>Templates are stubbed via {@link InMemoryTemplateResolver} — an in-memory map of {@code from →
 * content} — so the suite never touches the real filesystem. The production resolver, {@code
 * LocalRelativeTemplateResolver}, gets one end-to-end test exercising the repo-root escape guard (a
 * behavioural check the in-memory stub cannot cover).
 */
class TemplateScopeTest {

  private static PipelineModel parseWith(String yaml, InMemoryTemplateResolver r) {
    return TitanYamlParser.parse(yaml, r);
  }

  // ── happy path ───────────────────────────────────────────────────────────

  @Test
  void useInlinesTemplateStepsWithParamSubstitution() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./templates/gradleBuild.yaml",
                """
                params:
                  module: { required: true, type: string }
                  jdk:    { required: false, type: integer, default: 21 }
                steps:
                  - sh: "./gradlew :${param.module}:build -PjdkVersion=${param.jdk}"
                  - sh: "./gradlew :${param.module}:test"
                """);
    PipelineModel model =
        parseWith(
            """
                stages:
                  - stage: build
                    use:
                      from: ./templates/gradleBuild.yaml
                      with:
                        module: titan-server
                        jdk: 21
                """,
            r);
    assertEquals(1, model.getStages().size());
    StageModel stage = model.getStages().get(0);
    assertEquals("build", stage.getId());
    assertEquals(2, stage.getSteps().size(), "two template steps must be inlined");
    StepModel s0 = stage.getSteps().get(0);
    assertEquals("sh", s0.getDescriptorId());
    assertEquals("./gradlew :titan-server:build -PjdkVersion=21", s0.getArguments().get("value"));
    StepModel s1 = stage.getSteps().get(1);
    assertEquals("./gradlew :titan-server:test", s1.getArguments().get("value"));
  }

  @Test
  void defaultParamValueIsUsedWhenWithOmitsIt() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./templates/t.yaml",
                """
                params:
                  jdk: { required: false, type: integer, default: 21 }
                steps:
                  - sh: "echo ${param.jdk}"
                """);
    PipelineModel model =
        parseWith(
            """
                stages:
                  - stage: s
                    use:
                      from: ./templates/t.yaml
                """,
            r);
    assertEquals("echo 21", model.getStages().get(0).getSteps().get(0).getArguments().get("value"));
  }

  @Test
  void stringDefaultIsApplied() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./templates/t.yaml",
                """
                params:
                  greeting: { type: string, default: hello }
                steps:
                  - sh: "echo ${param.greeting}"
                """);
    PipelineModel model =
        parseWith(
            """
                stages:
                  - stage: s
                    use: { from: ./templates/t.yaml }
                """,
            r);
    assertEquals(
        "echo hello", model.getStages().get(0).getSteps().get(0).getArguments().get("value"));
  }

  @Test
  void templateStepsCoexistWithPlainStages() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                params:
                  m: { required: true, type: string }
                steps:
                  - sh: "build ${param.m}"
                """);
    PipelineModel model =
        parseWith(
            """
                stages:
                  - stage: a
                    use:
                      from: ./t.yaml
                      with: { m: foo }
                  - stage: b
                    steps:
                      - sh: "smoke"
                """,
            r);
    assertEquals(2, model.getStages().size());
    assertEquals(
        "build foo", model.getStages().get(0).getSteps().get(0).getArguments().get("value"));
    assertEquals("smoke", model.getStages().get(1).getSteps().get(0).getArguments().get("value"));
  }

  // ── error cases (design/56 §3) ───────────────────────────────────────────

  @Test
  void missingRequiredParamIsAParseError() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                params:
                  module: { required: true, type: string }
                steps:
                  - sh: "echo ${param.module}"
                """);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use: { from: ./t.yaml }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("required template param 'module' is missing"),
        "missing required param must be reported; was: " + ex.getMessage());
  }

  @Test
  void unknownParamInWithIsAParseError() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                params:
                  module: { required: true, type: string }
                steps:
                  - sh: "echo ${param.module}"
                """);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use:
                              from: ./t.yaml
                              with: { module: m, bogus: x }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("'bogus' is not a declared template param"),
        "unknown 'with' key must be reported; was: " + ex.getMessage());
  }

  @Test
  void integerParamRejectsNonIntegralValue() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                params:
                  n: { required: true, type: integer }
                steps:
                  - sh: "echo ${param.n}"
                """);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use:
                              from: ./t.yaml
                              with: { n: "not-a-number" }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("expected integer"),
        "type mismatch must be reported; was: " + ex.getMessage());
  }

  @Test
  void templateFileDoesNotExistError() {
    InMemoryTemplateResolver r = new InMemoryTemplateResolver();
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use: { from: ./does/not/exist.yaml }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("template file does not exist")
            && ex.getMessage().contains("./does/not/exist.yaml"),
        "missing template must be reported with the resolved path; was: " + ex.getMessage());
  }

  @Test
  void undeclaredParamReferenceIsAParseError() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                params:
                  m: { required: true, type: string }
                steps:
                  - sh: "echo ${param.other}"
                """);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use:
                              from: ./t.yaml
                              with: { m: x }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("references an undeclared param"),
        "undeclared param reference must be reported; was: " + ex.getMessage());
  }

  // ── mutual-exclusion guards ──────────────────────────────────────────────

  @Test
  void stageWithBothStepsAndUseIsAParseError() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                steps:
                  - sh: "x"
                """);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use: { from: ./t.yaml }
                            steps:
                              - sh: "y"
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("'use' and 'steps' are mutually exclusive"),
        "use+steps must be rejected; was: " + ex.getMessage());
  }

  @Test
  void stageWithBothUseAndMatrixIsAParseError() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                steps:
                  - sh: "x"
                """);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            matrix:
                              axes:
                                arch: [amd64]
                            use: { from: ./t.yaml }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("'use' and 'matrix' are mutually exclusive"),
        "use+matrix must be rejected; was: " + ex.getMessage());
  }

  @Test
  void stageWithBothUseAndEachIsAParseError() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                steps:
                  - sh: "x"
                """);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            each:
                              var: r
                              in: [a, b]
                            use: { from: ./t.yaml }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("'use' and 'each' are mutually exclusive"),
        "use+each must be rejected; was: " + ex.getMessage());
  }

  // ── recursive 'use:' guard (design/56 §5 — v1 nesting limit) ─────────────

  @Test
  void recursiveUseInTemplateIsAParseError() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./outer.yaml",
                """
                steps:
                  - use:
                      from: ./inner.yaml
                """)
            .with(
                "./inner.yaml",
                """
                steps:
                  - sh: "x"
                """);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use: { from: ./outer.yaml }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("nested 'use:' is not supported in v1"),
        "recursive use must be rejected; was: " + ex.getMessage());
  }

  // ── 'use:' object-shape validation ───────────────────────────────────────

  @Test
  void useMissingFromIsAParseError() {
    InMemoryTemplateResolver r = new InMemoryTemplateResolver();
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use:
                              with: { x: 1 }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("'from' is required"),
        "missing 'from' must be reported; was: " + ex.getMessage());
  }

  @Test
  void useWithAbsolutePathIsAParseError() {
    InMemoryTemplateResolver r = new InMemoryTemplateResolver();
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use: { from: /etc/passwd }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("must be a relative path, not absolute"),
        "absolute path must be rejected; was: " + ex.getMessage());
  }

  @Test
  void useWithSchemeIsAParseError() {
    InMemoryTemplateResolver r = new InMemoryTemplateResolver();
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                parseWith(
                    """
                        stages:
                          - stage: s
                            use: { from: "https://example.com/t.yaml" }
                        """,
                    r));
    assertTrue(
        ex.getMessage().contains("must be a local relative path"),
        "URI scheme must be rejected; was: " + ex.getMessage());
  }

  // ── path-traversal guard ─────────────────────────────────────────────────

  @Test
  void pathEscapingRepoRootIsAParseError(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
      throws Exception {
    // The production resolver computes the escape guard against the canonicalised baseDir.
    // The in-memory stub does not enforce the rule (it only does map lookups) so we use the
    // real LocalRelativeTemplateResolver here, anchored to a tempdir.
    java.nio.file.Path baseDir = tmp.resolve("repo");
    java.nio.file.Files.createDirectories(baseDir);
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parse(
                    """
                        stages:
                          - stage: s
                            use: { from: "../outside.yaml" }
                        """,
                    baseDir));
    assertTrue(
        ex.getMessage().contains("escapes the pipeline base directory"),
        "repo-root escape must be reported; was: " + ex.getMessage());
  }

  // ── multiple use stages in one pipeline ──────────────────────────────────

  @Test
  void multipleUseStagesAllInline() {
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                params:
                  m: { required: true, type: string }
                steps:
                  - sh: "do ${param.m}"
                """);
    PipelineModel model =
        parseWith(
            """
                stages:
                  - stage: a
                    use: { from: ./t.yaml, with: { m: alpha } }
                  - stage: b
                    use: { from: ./t.yaml, with: { m: beta } }
                """,
            r);
    assertEquals(2, model.getStages().size());
    assertEquals(
        "do alpha", model.getStages().get(0).getSteps().get(0).getArguments().get("value"));
    assertEquals("do beta", model.getStages().get(1).getSteps().get(0).getArguments().get("value"));
  }

  // ── stage-level scopes still flatten onto inlined template steps ─────────

  @Test
  void stageLevelCredentialsFlattenOntoInlinedSteps() {
    // Verifies that TemplateScope runs BEFORE the flatten-onto-steps scopes (credentials,
    // sshAgent, image, env) in SCOPES order — otherwise a stage-level `credentials:` would
    // target an empty `steps` list and silently no-op on the inlined template steps.
    InMemoryTemplateResolver r =
        new InMemoryTemplateResolver()
            .with(
                "./t.yaml",
                """
                steps:
                  - sh: "build"
                  - sh: "test"
                """);
    PipelineModel model =
        parseWith(
            """
                stages:
                  - stage: s
                    credentials:
                      - id: deploy-token
                        type: string
                        variable: TOKEN
                    use: { from: ./t.yaml }
                """,
            r);
    StageModel stage = model.getStages().get(0);
    assertEquals(2, stage.getSteps().size());
    for (StepModel step : stage.getSteps()) {
      assertEquals(
          1,
          step.getCredentials().size(),
          "stage credentials must be flattened onto each inlined template step (step="
              + step.getId()
              + ")");
      assertEquals("deploy-token", step.getCredentials().get(0).getId());
    }
  }

  // ── in-memory resolver for stubbing the filesystem in tests ──────────────

  /**
   * Tiny in-memory {@link TemplateResolver} that maps {@code from:} values directly to template
   * text. Skips path-escape checks (we cannot meaningfully compute a base directory for a map); one
   * separate test exercises the production resolver's escape guard end-to-end.
   */
  static final class InMemoryTemplateResolver implements TemplateResolver {

    private final Map<String, String> files = new LinkedHashMap<>();

    InMemoryTemplateResolver with(String from, String content) {
      files.put(from, content);
      return this;
    }

    @Override
    public TemplateContent resolve(String from, String errorContext) {
      // Re-use the production form check so URI-scheme / absolute-path rejection is enforced
      // even in tests (the corresponding tests above depend on it).
      LocalRelativeTemplateResolver.validateForm(from, errorContext);
      String text = files.get(from);
      if (text == null) {
        throw new PipelineParseException(
            errorContext
                + ": template file does not exist at resolved path '"
                + from
                + "' (from='"
                + from
                + "')");
      }
      return new TemplateContent(from, text);
    }
  }
}
