package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StageModel;
import io.adaptiq.titan.flow.model.TriggerModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

/**
 * Parser-validation test for the repo's dogfood pipeline at {@code /.titan/pipeline.yml} — the
 * "Titan compiles Titan" YAML anchoring docs/design/58-alignment-grooming.md §4 Sprint #1.
 *
 * <p>The contract: the dogfood YAML must remain grammar-valid as the PDL evolves. If a future
 * schema/parser change breaks the self-build pipeline, this test fails in CI before the change
 * merges. Gradle runs tests with the module directory as the working directory, so the file is
 * resolved relative to the project root one level up.
 */
class TitanSelfBuildPipelineTest {

  /** Locate {@code .titan/pipeline.yml} from the repo root, regardless of which module runs us. */
  private static Path locateDogfoodYaml() {
    // Walk up from the test CWD until we find a directory containing .titan/pipeline.yml.
    // Gradle invokes tests with the module subdir as CWD; running from the repo root also works.
    Path cur = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 6 && cur != null; i++) {
      Path candidate = cur.resolve(".titan").resolve("pipeline.yml");
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
      cur = cur.getParent();
    }
    throw new IllegalStateException(
        "could not locate .titan/pipeline.yml from CWD " + Paths.get("").toAbsolutePath());
  }

  @Test
  void dogfoodPipelineParsesAndValidates() throws Exception {
    String yaml = Files.readString(locateDogfoodYaml());
    PipelineModel model = TitanYamlParser.parseAndValidate(yaml);

    assertNotNull(model, "the parser must accept the dogfood YAML");
    assertEquals("linux", model.getAgent());
    assertFalse(model.getStages().isEmpty(), "the pipeline must define stages");
    assertFalse(model.getGates().isEmpty(), "the pipeline must define the E2E gate");
  }

  /**
   * The dogfood pipeline must keep exercising the PDL keywords this test asserts: triggers, matrix,
   * each, when, timeout (pipeline + stage), notify, parallel-via-shared-dependsOn. If a future edit
   * to {@code .titan/pipeline.yml} accidentally drops one, this test fails — keeping the dogfood
   * load-bearing for grammar coverage.
   */
  @Test
  void dogfoodPipelineExercisesShippedPdlKeywords() throws Exception {
    String yaml = Files.readString(locateDogfoodYaml());

    // The raw YAML must contain these keywords — the parser would silently accept a YAML that
    // dropped some of them (every key is optional except `stages:`), so a textual check is what
    // pins the coverage promise.
    assertTrue(yaml.contains("triggers:"), "must declare triggers:");
    assertTrue(yaml.contains("matrix:"), "must exercise matrix: fan-out");
    assertTrue(yaml.contains("each:"), "must exercise each: fan-out");
    assertTrue(yaml.contains("when:"), "must exercise when: gating");
    assertTrue(yaml.contains("timeout:"), "must exercise timeout: scope");
    assertTrue(yaml.contains("notify:"), "must exercise notify: lifecycle hooks");

    // Two stages sharing the same `dependsOn: [Lint]` is how parallelism is expressed in Titan
    // (sibling DAG nodes with the same predecessor) — the legacy `parallel: true` key is
    // explicitly rejected by the parser.
    PipelineModel model = TitanYamlParser.parseAndValidate(yaml);
    long lintFanOut =
        model.getStages().stream().filter(s -> s.getDependsOn().contains("Lint")).count();
    assertTrue(
        lintFanOut >= 2,
        "at least 2 stages must depend on Lint (the parallel fan-out); was " + lintFanOut);
  }

  /**
   * The post-merge auto-publish-and-rollout contract (#950). The dogfood pipeline MUST: (1) fire on
   * a GitHub push to trunk, (2) tag the three required image tags, (3) ship a Rollout Restart stage
   * gated on a successful Publish. If any of these regresses, the loop's friction returns and the
   * CTO is back to manual `task deploy:k3s:trunk` after every merge — so this test pins all three.
   */
  @Test
  void dogfoodPipelineImplementsPostMergeContract() throws Exception {
    String yaml = Files.readString(locateDogfoodYaml());
    PipelineModel model = TitanYamlParser.parseAndValidate(yaml);

    // (1) GitHub push-to-trunk trigger present. We accept the cron safety-net alongside but the
    // github push trigger must exist — that is the contract.
    boolean hasGithubPushOnTrunk =
        model.getTriggers().stream()
            .map(TriggerModel::getGithub)
            .filter(g -> g != null)
            .anyMatch(g -> g.getBranches().contains("trunk") && g.getEvents().contains("push"));
    assertTrue(hasGithubPushOnTrunk, "a github: push trigger on branch 'trunk' is required (#950)");

    // (2) The three required tag literals appear in the publish stage. Textual assertion is
    // intentional: the parser stores step `sh` bodies as opaque strings, so a string check is
    // the precise way to pin the tag scheme contract.
    assertTrue(yaml.contains(":${SHA_TAG}"), "publish must push the immutable trunk-<sha> tag");
    assertTrue(yaml.contains(":latest-trunk"), "publish must push the :latest-trunk floating tag");
    assertTrue(yaml.contains(":demo-latest"), "publish must push the :demo-latest tag (#950)");

    // (3) A Rollout Restart stage exists, fans in from EVERY Publish Images matrix cell, and is
    // trunk-only. Per #398, `dependsOn: [Publish Images]` is expanded at bake time to the cell
    // names of the matrix prototype (component=server/ui/worker) — so the rig only restarts
    // after all three images publish successfully. We assert the post-expansion fan-in here.
    StageModel rollout =
        model.getStages().stream()
            .filter(s -> "Rollout Restart".equals(s.getName()))
            .findFirst()
            .orElseThrow(
                () ->
                    new AssertionError(
                        "Rollout Restart stage is required to close the auto-deploy loop (#950)"));
    assertTrue(
        rollout.getDependsOn().stream().anyMatch(d -> d.startsWith("Publish Images")),
        "Rollout Restart must dependOn the Publish Images cells (fan-in via #398 expansion); was "
            + rollout.getDependsOn());
    assertEquals(
        3,
        rollout.getDependsOn().size(),
        "Rollout Restart must wait on ALL 3 Publish Images cells (server/ui/worker), not a subset"
            + " — a partial publish must not trigger a rig restart; was "
            + rollout.getDependsOn());
    assertEquals(
        "params.branch == 'trunk'",
        rollout.getWhen(),
        "Rollout Restart must be trunk-only — PR builds never poke the rig");
  }
}
