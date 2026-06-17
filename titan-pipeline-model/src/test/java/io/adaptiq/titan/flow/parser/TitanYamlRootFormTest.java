package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.FailurePolicy;
import io.adaptiq.titan.flow.model.PipelineModel;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the design/29 §3 / design/38 Part A document-shape change: the pipeline body is
 * accepted both at the document root (the canonical form) and under a legacy {@code titan:}
 * wrapper. Pure — no DB, no engine.
 */
class TitanYamlRootFormTest {

  /** The same pipeline body, root-level. */
  private static final String ROOT_FORM =
      "agent: linux\n"
          + "failurePolicy: blockOnFailure\n"
          + "stages:\n"
          + "  - stage: Build\n"
          + "    steps:\n"
          + "      - sh: mvn package\n"
          + "  - stage: Test\n"
          + "    dependsOn: [Build]\n"
          + "    steps:\n"
          + "      - sh: mvn verify\n";

  /** The same pipeline body, wrapped under a legacy {@code titan:} key. */
  private static final String WRAPPED_FORM =
      "titan:\n"
          + "  agent: linux\n"
          + "  failurePolicy: blockOnFailure\n"
          + "  stages:\n"
          + "    - stage: Build\n"
          + "      steps:\n"
          + "        - sh: mvn package\n"
          + "    - stage: Test\n"
          + "      dependsOn: [Build]\n"
          + "      steps:\n"
          + "        - sh: mvn verify\n";

  @Test
  void parsesTheRootLevelForm() {
    PipelineModel model = TitanYamlParser.parseAndValidate(ROOT_FORM);
    assertEquals("linux", model.getAgent());
    assertEquals(FailurePolicy.BLOCK_ON_FAILURE, model.getFailurePolicy());
    assertEquals(2, model.getStages().size());
    assertEquals("Build", model.getStage("build").getName());
  }

  @Test
  void parsesTheLegacyTitanWrapperForm() {
    PipelineModel model = TitanYamlParser.parseAndValidate(WRAPPED_FORM);
    assertEquals("linux", model.getAgent());
    assertEquals(FailurePolicy.BLOCK_ON_FAILURE, model.getFailurePolicy());
    assertEquals(2, model.getStages().size());
  }

  /** Both shapes must produce the identical model — the wrapper is pure sugar. */
  @Test
  void bothShapesProduceTheIdenticalModel() {
    PipelineModel root = TitanYamlParser.parse(ROOT_FORM);
    PipelineModel wrapped = TitanYamlParser.parse(WRAPPED_FORM);
    assertEquals(root.getStages().size(), wrapped.getStages().size());
    assertEquals(root.getAgent(), wrapped.getAgent());
    assertEquals(root.getFailurePolicy(), wrapped.getFailurePolicy());
    assertEquals(root.getStage("test").getDependsOn(), wrapped.getStage("test").getDependsOn());
  }

  /** A root-level definition with an unknown top-level key is still rejected loudly. */
  @Test
  void rejectsUnknownRootKeyInRootForm() {
    String bad = "agent: linux\nbogus: nope\nstages:\n  - stage: A\n    steps: []\n";
    PipelineParseException ex =
        assertThrows(PipelineParseException.class, () -> TitanYamlParser.parse(bad));
    assertTrue(ex.getMessage().contains("bogus"), ex.getMessage());
    assertTrue(ex.getMessage().startsWith("pipeline:"), "context label is 'pipeline'");
  }

  /** A {@code titan:} key whose value is not an object is a malformed wrapper — rejected. */
  @Test
  void rejectsTitanKeyThatIsNotAnObject() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class, () -> TitanYamlParser.parse("titan: just-a-string\n"));
    assertTrue(ex.getMessage().contains("'titan' must be an object"), ex.getMessage());
  }

  /** An empty stages list still fails — the error names the right context. */
  @Test
  void rootFormStillRequiresNonEmptyStages() {
    PipelineParseException ex =
        assertThrows(
            PipelineParseException.class,
            () -> TitanYamlParser.parse("agent: linux\nstages: []\n"));
    assertTrue(ex.getMessage().contains("pipeline.stages"), ex.getMessage());
  }
}
