package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parses {@code e2e/pipelines/secret-redaction/titan-pipeline.yml} (issue #1135) and asserts the
 * parse-time invariants the e2e secret-redaction fixture depends on.
 *
 * <p>The fixture exists to prove the runtime contract that PDL secret references ({@code ${{
 * secrets.<id> }}}) are redacted in every log surface. This test guards the SHAPE the runtime
 * contract is built on:
 *
 * <ul>
 *   <li>Top-level non-secret env (REGION) coexists with the secret env binding.
 *   <li>The {@code use-secret} stage exists with exactly one step.
 *   <li>The step's env carries {@code API_TOKEN} bound to the literal expression {@code ${{
 *       secrets.E2E_REDACTION_TOKEN }}} — the parser MUST preserve the expression string; runtime
 *       resolves it. A regression that eagerly resolved or stripped the expression at parse time
 *       would break redaction silently (no canonical name to redact).
 * </ul>
 *
 * <p>Adversarial by design — the assertions name the customer-shaped expression literally, not just
 * "non-null". A change that, say, normalised the spaces inside the {@code ${{ ... }}} would fail
 * here and force a conscious decision rather than a silent behaviour shift.
 */
class SecretRedactionFixtureTest {

  private static final String FIXTURE_RELATIVE =
      "e2e/pipelines/secret-redaction/titan-pipeline.yml";

  /** Walks up from this module to find the repo root, then reads the fixture YAML. */
  private static String readFixture() throws IOException {
    Path cur = Path.of("").toAbsolutePath();
    for (int i = 0; i < 6 && cur != null; i++) {
      Path candidate = cur.resolve(FIXTURE_RELATIVE);
      if (Files.isRegularFile(candidate)) {
        return Files.readString(candidate);
      }
      cur = cur.getParent();
    }
    throw new IOException("could not locate " + FIXTURE_RELATIVE);
  }

  @Test
  void fixtureParsesAndCarriesPlainTopLevelEnv() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());
    Map<String, String> env = model.getEnv();
    assertNotNull(env, "top-level env: should be parsed");
    assertEquals(
        "eu-central-1",
        env.get("REGION"),
        "plain non-secret env must coexist with the per-step secret env binding");
  }

  @Test
  void fixtureDeclaresSingleUseSecretStage() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());
    assertEquals(
        1,
        model.getStages().size(),
        "fixture is intentionally single-stage — keeps the redaction signal undiluted");
    StageModel stage = model.getStages().get(0);
    assertEquals("use-secret", stage.getName());
    assertEquals(
        1,
        stage.getSteps().size(),
        "use-secret stage is intentionally single-step — the four redaction angles live inside"
            + " one shell so a single SUCCESS run proves the contract end-to-end");
  }

  @Test
  void stepEnvPreservesRawSecretExpression() throws IOException {
    PipelineModel model = TitanYamlParser.parse(readFixture());
    StepModel step = model.getStages().get(0).getSteps().get(0);
    Map<String, String> stepEnv = step.getEnv();
    assertNotNull(stepEnv, "step must declare a per-step env: binding for the secret");
    String apiToken = stepEnv.get("API_TOKEN");
    assertNotNull(apiToken, "API_TOKEN must be present in the step env");
    // The runtime needs the EXACT expression to look up the credential. A parser that
    // eagerly stripped or normalised the expression would silently break redaction —
    // the canonical secret-id would be lost.
    assertTrue(
        apiToken.contains("secrets.E2E_REDACTION_TOKEN"),
        "API_TOKEN must carry the secret reference expression preserved verbatim, got: "
            + apiToken);
    assertTrue(
        apiToken.startsWith("${{") && apiToken.endsWith("}}"),
        "secret expression delimiters must be preserved by the parser, got: " + apiToken);
  }

  @Test
  void stepShellAttacksRedactionFromMultipleAngles() throws IOException {
    // The runtime carries the shell body inside the step's arguments map (sh-step's
    // canonical argument key). We assert against the raw YAML for resilience against
    // a future argument-key rename — the contract is "the four leak angles MUST live
    // in the use-secret step", not "they MUST live under key X".
    String raw = readFixture();
    // Each line below is one of the redaction-attack angles the fixture is supposed
    // to exercise. If a future cleanup deletes one, this test fails and forces a
    // conscious decision rather than a silent contract weakening.
    assertTrue(raw.contains("direct:"), "direct echo angle missing from fixture shell");
    assertTrue(raw.contains("embedded:"), "embedded-arg angle missing from fixture shell");
    assertTrue(raw.contains("second-use:"), "second-use angle missing from fixture shell");
    assertTrue(
        raw.contains("printenv"), "printenv (export-line leak) angle missing from fixture shell");
    // Sanity: assert the step model parses (used by the other tests too — fail-fast).
    PipelineModel model = TitanYamlParser.parse(raw);
    StepModel step = model.getStages().get(0).getSteps().get(0);
    assertNotNull(step, "use-secret step must parse");
  }
}
