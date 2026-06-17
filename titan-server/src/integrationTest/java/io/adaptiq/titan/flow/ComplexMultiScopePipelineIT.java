package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.TaskQueueRow;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Breadth-coverage integration tests proving Titan bakes + advances a <strong>wide range of
 * complex, realistic, multi-scope pipelines</strong> against real PostgreSQL — not single-feature
 * toys. Each fixture combines 3+ PDL scopes (matrix / each / precondition / gate / onFailure /
 * notify / env-precedence / dependsOn diamonds / when / setOutput) the way a real CI pipeline does.
 *
 * <p><strong>What this layer actually exercises (honest framing).</strong> The worker is
 * <em>stubbed</em>: the test claims each {@code EXECUTE_COMMAND} task the orchestrator dispatches
 * and completes it with a synthetic {@code result_json}. So these tests drive the <em>real
 * orchestration engine</em> — bake (YAML→DAG, matrix/each parse-time expansion, when evaluation),
 * {@code advance()} (dispatch, dependency gating, precondition evaluation against published
 * outputs, gate parking, onFailure firing, failure propagation, env merge into the dispatched
 * payload) — but they do <em>not</em> execute the real step handlers (sh/junit/k8sApply/…). They
 * prove the pipeline <em>shape and control-flow</em> are honoured end-to-end, which is the breadth
 * question. Step-handler behaviour is the worker TCK's job.
 *
 * <p>Assertions are adversarial: matrix {@code exclude} actually drops a cell; a precondition that
 * evaluates false SKIPs its downstream; an {@code onFailure} handler fires only on a real upstream
 * failure; env precedence resolves pipeline&lt;stage&lt;step in the dispatched payload.
 */
@Testcontainers
class ComplexMultiScopePipelineIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;
  private long buildId;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(8);
    ds = new HikariDataSource(cfg);

    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA IF EXISTS titan CASCADE");
      st.execute("CREATE SCHEMA titan");
    }
    Flyway.configure(getClass().getClassLoader())
        .dataSource(ds)
        .schemas("titan")
        .defaultSchema("titan")
        .locations(
            "classpath:io/adaptiq/titan/db/migration",
            "classpath:io/adaptiq/titan/db/migration-postgresql")
        .load()
        .migrate();

    stores = TitanStores.forDataSource(ds);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ════════════════════════════════════════════════════════════════════════
  // 1. POLYGLOT CI — matrix(os×jdk, exclude, fail_fast) + junit + archive + fan-in
  // ════════════════════════════════════════════════════════════════════════

  /**
   * The polyglot matrix bakes into exactly the surviving cells: os×jdk minus the excluded
   * {windows,17} cell — 3 cell stages, each with its 3 steps (sh/junit/archiveArtifacts), plus a
   * fan-in Publish stage whose {@code dependsOn:[Build]} rewrote to depend on every cell.
   */
  @Test
  void polyglotMatrixExpandsExcludesCellAndFansInToPublish() throws Exception {
    bake("complex-polyglot-matrix.yml");
    Map<String, FlowNodeRow> nodes = nodesById();

    // The 3 surviving cell stages exist; the excluded windows/17 cell does NOT.
    assertTrue(nodes.containsKey("build-os-linux-jdk-17"), "linux/17 cell stage materialised");
    assertTrue(nodes.containsKey("build-os-linux-jdk-21"), "linux/21 cell stage materialised");
    assertTrue(nodes.containsKey("build-os-windows-jdk-21"), "windows/21 cell stage materialised");
    assertFalse(
        nodes.containsKey("build-os-windows-jdk-17"),
        "the excluded {windows,17} cell must NOT be materialised");

    // Each cell carries its 3 steps; assert one cell's step descriptors.
    assertEquals("sh", nodes.get("build-os-linux-jdk-17-s0").stepDescriptor);
    assertEquals("junit", nodes.get("build-os-linux-jdk-17-s1").stepDescriptor);
    assertEquals("archiveArtifacts", nodes.get("build-os-linux-jdk-17-s2").stepDescriptor);

    // Publish fan-in depends on all 3 cells (issue #398 dependsOn rewrite).
    assertTrue(nodes.containsKey("publish"), "the fan-in Publish stage materialised");
    String publishParents = nodes.get("publish").parentIds;
    assertTrue(publishParents.contains("build-os-linux-jdk-17"), "Publish waits on linux/17");
    assertTrue(publishParents.contains("build-os-linux-jdk-21"), "Publish waits on linux/21");
    assertTrue(publishParents.contains("build-os-windows-jdk-21"), "Publish waits on windows/21");
  }

  /** The all-green matrix drives every cell + the fan-in Publish to SUCCESS. */
  @Test
  void polyglotMatrixAllGreenCompletesSuccessfully() throws Exception {
    bake("complex-polyglot-matrix.yml");
    DriveResult r = drive(Outcomes.allSuccess());

    assertTrue(r.finished);
    assertEquals("SUCCESS", r.buildResult);
    assertEquals("SUCCESS", status("build-os-linux-jdk-17"));
    assertEquals("SUCCESS", status("build-os-linux-jdk-21"));
    assertEquals("SUCCESS", status("build-os-windows-jdk-21"));
    assertEquals("SUCCESS", status("publish"));
  }

  /**
   * ADVERSARIAL + ENGINE-PROBE: one matrix cell's build step FAILS under {@code fail_fast: true}
   * (#1213). The failed cell's stage FAILs and — because the matrix declares fail-fast — every
   * still-active sibling cell is cancelled to {@code ABORTED} (cause preserved: the failed cell
   * stays FAILED, not ABORTED). The fan-in Publish is SKIPPED (a FAILED ancestor) and the build is
   * FAILED. This is the inverse of the pre-#1213 behaviour, where siblings ran to SUCCESS.
   */
  @Test
  void polyglotMatrixOneCellFailsAbortsSiblingsUnderFailFast() throws Exception {
    bake("complex-polyglot-matrix.yml");
    // Fail only the linux/17 cell's build step (-s0). Its junit/archive (-s1/-s2) never dispatch.
    DriveResult r = drive(Outcomes.failNodes(Set.of("build-os-linux-jdk-17-s0")));

    assertTrue(r.finished, "the build must reach a terminal state");
    assertEquals("FAILED", r.buildResult, "a failed matrix cell fails the build");

    assertEquals("FAILED", status("build-os-linux-jdk-17"), "the failed cell's stage is FAILED");
    // #1213: fail_fast: true cancels the in-flight siblings — they ABORT, they do NOT run to green.
    assertEquals(
        "ABORTED",
        status("build-os-linux-jdk-21"),
        "sibling cell is ABORTED by fail_fast sibling-cancellation (#1213)");
    assertEquals(
        "ABORTED",
        status("build-os-windows-jdk-21"),
        "the other sibling cell is also ABORTED — fail_fast cascade");
    assertEquals(
        "SKIPPED",
        status("publish"),
        "the fan-in Publish has a FAILED ancestor cell -> SKIPPED under blockOnFailure");
    assertEquals(0, stores.flowNodes().countNonTerminal(buildId), "no node left non-terminal");
  }

  /**
   * The contrast case for #1213: with {@code fail_fast: false} a failed cell does NOT cancel its
   * siblings — they run to SUCCESS, exactly as the pre-#1213 default behaviour. Only the failed
   * cell's descendants + the fan-in Publish are SKIPPED. Proves the flag, not a blanket change.
   */
  @Test
  void polyglotMatrixFailFastFalseLetsSiblingsCompleteSuccessfully() throws Exception {
    bake("complex-polyglot-matrix-no-failfast.yml");
    DriveResult r = drive(Outcomes.failNodes(Set.of("build-os-linux-jdk-17-s0")));

    assertTrue(r.finished, "the build must reach a terminal state");
    assertEquals("FAILED", r.buildResult, "a failed matrix cell still fails the build");

    assertEquals("FAILED", status("build-os-linux-jdk-17"), "the failed cell's stage is FAILED");
    assertEquals(
        "SUCCESS",
        status("build-os-linux-jdk-21"),
        "fail_fast: false — the sibling runs to completion");
    assertEquals(
        "SUCCESS",
        status("build-os-windows-jdk-21"),
        "fail_fast: false — the other sibling runs to completion");
    assertEquals("SKIPPED", status("publish"), "fan-in still SKIPPED behind the FAILED cell");
    assertEquals(0, stores.flowNodes().countNonTerminal(buildId), "no node left non-terminal");
  }

  /**
   * ADVERSARIAL / SAD PATH for #1213: a fail-fast matrix throttled to {@code maxParallel: 1}. Only
   * one cell ever runs at a time, so when the first cell fails the remaining cells are still QUEUED
   * behind the throttle (never dispatched). They must be ABORTED before they dispatch and the build
   * must converge — no hung RUNNING/PENDING node behind the throttle.
   */
  @Test
  void polyglotMatrixFailFastAbortsCellsQueuedBehindMaxParallel() throws Exception {
    bake("complex-polyglot-matrix-throttled.yml");
    DriveResult r = drive(Outcomes.failNodes(Set.of("build-os-linux-jdk-17-s0")));

    assertTrue(r.finished, "the throttled fail-fast build must still converge to terminal");
    assertEquals("FAILED", r.buildResult);
    assertEquals("FAILED", status("build-os-linux-jdk-17"), "the first (only-running) cell FAILED");
    assertEquals(
        "ABORTED",
        status("build-os-linux-jdk-21"),
        "a cell queued behind maxParallel=1 is ABORTED before it dispatches");
    assertEquals(
        "ABORTED",
        status("build-os-windows-jdk-21"),
        "the other throttled cell is also ABORTED, never dispatched");
    assertEquals(
        0, stores.flowNodes().countNonTerminal(buildId), "no hung node behind the throttle");
  }

  // ════════════════════════════════════════════════════════════════════════
  // 2. RELEASE — gate(requiresApproval) + gitTag + notify + 3-tier env + setOutput
  // ════════════════════════════════════════════════════════════════════════

  /**
   * The release pipeline parks at the gate; an authorised approval releases it; the Release stage's
   * gitTag step dispatches with the resolved version AND the correctly-merged env (step-layer
   * CHANNEL=hotfix wins over stage CHANNEL=ga over pipeline CHANNEL=stable).
   */
  @Test
  void releaseGateApprovalDrivesTagWithResolvedVersionAndEnvPrecedence() throws Exception {
    bake("complex-release-gate-tag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);

    // Drive to the gate. Build publishes version=2.7.0.
    driveToSteadyState(
        orch, Outcomes.withOutputs("build-s0", Map.<String, Object>of("version", "2.7.0")));

    assertEquals("RUNNING", status("release-approval"), "the build parks at the gate");
    assertEquals("PENDING", status("release"), "Release must not start before approval");

    // An authorised release-manager approves.
    GateService.GateOutcome outcome =
        GateService.decide(
            stores,
            buildId,
            "release-approval",
            "rel-mgr",
            Set.of("release-managers"),
            GateService.Decision.APPROVED);
    assertTrue(outcome.applied(), outcome.message());

    // Capture the gitTag step's dispatched payload as the DAG drives on.
    DriveResult r =
        driveCapturing(
            orch,
            Outcomes.withOutputs("build-s0", Map.<String, Object>of("version", "2.7.0")),
            "release-s0");

    assertTrue(r.finished);
    assertEquals("SUCCESS", r.buildResult);
    assertEquals("SUCCESS", status("release-approval"));
    assertEquals("SUCCESS", status("release"));

    String tagPayload = r.captured;
    assertNotNull(tagPayload, "the gitTag step must have dispatched");
    // setOutput version flowed into the tag (no raw template survived).
    assertTrue(tagPayload.contains("v2.7.0"), "tag resolved to v2.7.0: " + tagPayload);
    assertFalse(tagPayload.contains("${{"), "no raw ${{ }} template in payload: " + tagPayload);
    // env precedence: step CHANNEL=hotfix wins; stage=ga and pipeline=stable must NOT appear as the
    // CHANNEL value. We assert the winning value is present and the env carries the pipeline-only
    // key too (RELEASE_BOT inherited).
    assertTrue(tagPayload.contains("hotfix"), "step-layer env CHANNEL=hotfix won: " + tagPayload);
    assertTrue(
        tagPayload.contains("titan-ci"),
        "pipeline-layer env RELEASE_BOT inherited into the step: " + tagPayload);
  }

  /** ADVERSARIAL: rejecting the release gate fails the build and SKIPs the Release stage + tag. */
  @Test
  void releaseGateRejectionBlocksTheTag() throws Exception {
    bake("complex-release-gate-tag.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    driveToSteadyState(
        orch, Outcomes.withOutputs("build-s0", Map.<String, Object>of("version", "2.7.0")));

    GateService.GateOutcome outcome =
        GateService.decide(
            stores,
            buildId,
            "release-approval",
            "rel-mgr",
            Set.of("release-managers"),
            GateService.Decision.REJECTED);
    assertTrue(outcome.applied());

    driveToSteadyState(orch, Outcomes.allSuccess());
    assertEquals("FAILED", stores.builds().findById(buildId).orElseThrow().status);
    assertEquals("FAILED", status("release-approval"));
    assertEquals("SKIPPED", status("release"), "the rejected gate's downstream Release is SKIPPED");
    assertEquals("SKIPPED", status("release-s0"), "the gitTag step is SKIPPED");
  }

  // ════════════════════════════════════════════════════════════════════════
  // 3. DEPLOY WITH RECOVERY — k8sApply + httpRequest + precondition + onFailure + env
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Happy path: Deploy publishes healthy=true, the precondition passes, Promote runs, and the
   * Rollback onFailure handler is SKIPPED (no upstream failed). Also asserts the httpRequest step's
   * env precedence (step ENVIRONMENT=deploy-step wins).
   */
  @Test
  void deployHealthyPromotesAndRollbackHandlerIsSkipped() throws Exception {
    bake("complex-deploy-recovery.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    DriveResult r =
        driveCapturing(
            orch,
            Outcomes.withOutputs("deploy-s2", Map.<String, Object>of("healthy", true)),
            "deploy-s1");

    assertTrue(r.finished);
    assertEquals("SUCCESS", r.buildResult);
    assertEquals("SUCCESS", status("deploy"));
    assertEquals("SUCCESS", status("deploy-healthy"), "the precondition passed");
    assertEquals("SUCCESS", status("promote"));
    assertEquals("SKIPPED", status("rollback"), "no upstream failed -> Rollback handler SKIPPED");

    // env precedence on the httpRequest step (deploy-s1): step ENVIRONMENT=deploy-step wins.
    assertNotNull(r.captured, "httpRequest step must have dispatched");
    assertTrue(
        r.captured.contains("deploy-step"),
        "step-layer ENVIRONMENT=deploy-step won over stage/pipeline: " + r.captured);
  }

  /**
   * ADVERSARIAL: Deploy publishes healthy=false. The precondition FAILS, Promote is SKIPPED, and —
   * because Deploy itself succeeded but the precondition (a Deploy descendant) failed — the
   * Rollback {@code onFailure:[Deploy,Promote]} handler does NOT fire (neither Deploy nor Promote
   * reached terminal FAILED; the precondition node did). This documents the precise onFailure
   * contract: it keys on the LISTED upstreams' terminal FAILED, not on any failure in the DAG.
   */
  @Test
  void deployUnhealthyPreconditionFailsAndPromoteSkipped() throws Exception {
    bake("complex-deploy-recovery.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    DriveResult r =
        drive(orch, Outcomes.withOutputs("deploy-s2", Map.<String, Object>of("healthy", false)));

    assertTrue(r.finished);
    assertEquals("FAILED", r.buildResult, "a false health precondition fails the build");
    assertEquals("SUCCESS", status("deploy"), "Deploy itself succeeded");
    assertEquals("FAILED", status("deploy-healthy"), "the health precondition is FAILED");
    assertEquals("SKIPPED", status("promote"), "Promote is SKIPPED behind the failed precondition");
    // Neither Deploy nor Promote reached terminal FAILED -> handler does not fire.
    assertEquals(
        "SKIPPED",
        status("rollback"),
        "onFailure:[Deploy,Promote] keys on those stages' FAILED — neither failed, so SKIPPED");
    assertEquals(0, stores.flowNodes().countNonTerminal(buildId));
  }

  /**
   * ADVERSARIAL recovery path: the Deploy stage's k8sApply step FAILS. Promote is SKIPPED, and the
   * Rollback {@code onFailure:[Deploy,Promote]} handler FIRES (Deploy reached terminal FAILED),
   * runs its rollback step to SUCCESS — but the build stays FAILED (handler is recovery, not
   * absolution).
   */
  @Test
  void deployFailureFiresRollbackHandlerButBuildStaysFailed() throws Exception {
    bake("complex-deploy-recovery.yml");
    DriveResult r = drive(Outcomes.failNodes(Set.of("deploy-s0")));

    assertTrue(r.finished);
    assertEquals(
        "FAILED", r.buildResult, "build stays FAILED — handler is recovery, not absolution");
    assertEquals("FAILED", status("deploy"), "Deploy FAILED on its k8sApply step");
    assertEquals("SKIPPED", status("promote"), "Promote SKIPPED behind the failed Deploy");
    assertEquals(
        "SUCCESS", status("rollback"), "the onFailure Rollback handler fired and succeeded");
  }

  // ════════════════════════════════════════════════════════════════════════
  // 4. FAN-OUT / FAN-IN with precondition + when guards
  // ════════════════════════════════════════════════════════════════════════

  /** All suites green + deploy=true: the diamond + precondition + when stage all reach SUCCESS. */
  @Test
  void fanoutAllGreenWithDeployEnabledCompletesEntireDiamond() throws Exception {
    bake("complex-fanout-precondition.yml", "{\"deploy\": true}");
    DriveResult r =
        drive(
            Outcomes.withOutputsPerNode(
                Map.of(
                    "unit-s1", Map.<String, Object>of("passed", true),
                    "integration-s1", Map.<String, Object>of("passed", true))));

    assertTrue(r.finished);
    assertEquals("SUCCESS", r.buildResult);
    assertEquals("SUCCESS", status("unit"));
    assertEquals("SUCCESS", status("integration"));
    assertEquals("SUCCESS", status("quality-gate"), "both-green precondition passed");
    assertEquals("SUCCESS", status("package"));
    assertEquals("SUCCESS", status("deploy-staging"), "when:params.deploy==true -> stage ran");
  }

  /**
   * ADVERSARIAL: deploy=false bakes the when-gated Deploy Staging stage as SKIPPED (when is
   * evaluated at bake time), while the rest of the diamond still completes SUCCESS.
   */
  @Test
  void fanoutWhenFalseSkipsDeployStageAtBake() throws Exception {
    bake("complex-fanout-precondition.yml", "{\"deploy\": false}");
    // when:false is materialised SKIPPED at bake — assert before driving.
    assertEquals("SKIPPED", status("deploy-staging"), "when:false stage baked SKIPPED");

    DriveResult r =
        drive(
            Outcomes.withOutputsPerNode(
                Map.of(
                    "unit-s1", Map.<String, Object>of("passed", true),
                    "integration-s1", Map.<String, Object>of("passed", true))));
    assertTrue(r.finished);
    assertEquals("SUCCESS", r.buildResult, "a SKIPPED when-stage does not fail the build");
    assertEquals("SUCCESS", status("package"));
    assertEquals("SKIPPED", status("deploy-staging"));
  }

  /**
   * ADVERSARIAL: one arm of the diamond reports passed=false, so the AND precondition FAILS, and
   * Package + Deploy Staging are SKIPPED behind it.
   */
  @Test
  void fanoutOneArmFailsPreconditionAndBlocksDownstream() throws Exception {
    bake("complex-fanout-precondition.yml", "{\"deploy\": true}");
    DriveResult r =
        drive(
            Outcomes.withOutputsPerNode(
                Map.of(
                    "unit-s1", Map.<String, Object>of("passed", true),
                    "integration-s1",
                        Map.<String, Object>of("passed", false)))); // integration arm not green

    assertTrue(r.finished);
    assertEquals("FAILED", r.buildResult);
    assertEquals("SUCCESS", status("unit"));
    assertEquals("SUCCESS", status("integration"), "the step ran fine; only its output was false");
    assertEquals("FAILED", status("quality-gate"), "the AND precondition fails on a false arm");
    assertEquals("SKIPPED", status("package"), "Package SKIPPED behind the failed gate");
    assertEquals(
        "SKIPPED", status("deploy-staging"), "Deploy Staging SKIPPED behind the failed gate");
    assertEquals(0, stores.flowNodes().countNonTerminal(buildId));
  }

  // ════════════════════════════════════════════════════════════════════════
  // 5. EACH — 1D fan-out across regions + env + fan-in
  // ════════════════════════════════════════════════════════════════════════

  /** The each: stage expands into one cell per region; the fan-in Verify waits on all of them. */
  @Test
  void eachExpandsPerRegionAndFansInToVerify() throws Exception {
    bake("complex-each-regions.yml");
    Map<String, FlowNodeRow> nodes = nodesById();

    assertTrue(nodes.containsKey("deploy-region-us-east"), "us-east cell materialised");
    assertTrue(nodes.containsKey("deploy-region-eu-west"), "eu-west cell materialised");
    assertTrue(nodes.containsKey("deploy-region-ap-south"), "ap-south cell materialised");

    String verifyParents = nodes.get("verify").parentIds;
    assertTrue(verifyParents.contains("deploy-region-us-east"), "Verify waits on us-east");
    assertTrue(verifyParents.contains("deploy-region-eu-west"), "Verify waits on eu-west");
    assertTrue(verifyParents.contains("deploy-region-ap-south"), "Verify waits on ap-south");
  }

  /**
   * The each fan-out drives every region cell to SUCCESS, and each cell's dispatched payload
   * carries its own {@code ${each.region}} substitution (us-east in the us-east cell, etc.).
   */
  @Test
  void eachAllRegionsSucceedWithPerCellSubstitution() throws Exception {
    bake("complex-each-regions.yml");
    TitanOrchestrator orch = new TitanOrchestrator(stores, buildId);
    DriveResult r = driveCapturing(orch, Outcomes.allSuccess(), "deploy-region-eu-west-s0");

    assertTrue(r.finished);
    assertEquals("SUCCESS", r.buildResult);
    assertEquals("SUCCESS", status("deploy-region-us-east"));
    assertEquals("SUCCESS", status("deploy-region-eu-west"));
    assertEquals("SUCCESS", status("deploy-region-ap-south"));
    assertEquals("SUCCESS", status("verify"));

    assertNotNull(r.captured, "the eu-west cell step must have dispatched");
    assertTrue(
        r.captured.contains("eu-west"),
        "the eu-west cell's ${each.region} resolved to eu-west: " + r.captured);
    assertFalse(r.captured.contains("${each."), "no raw ${each.} template survived: " + r.captured);
  }

  /**
   * ADVERSARIAL (#1213): one region cell FAILS. The fan-in Verify is SKIPPED (a FAILED ancestor),
   * the build is FAILED, and — sharing the matrix fail-fast mechanism — the sibling region cells
   * are cancelled to ABORTED. {@code each:} declares no {@code fail_fast} key, so it defaults to
   * fail-fast (GitHub Actions / GitLab CI / Buildkite semantics), matching {@code matrix:}.
   */
  @Test
  void eachOneRegionFailsAbortsSiblingsUnderFailFast() throws Exception {
    bake("complex-each-regions.yml");
    DriveResult r = drive(Outcomes.failNodes(Set.of("deploy-region-us-east-s0")));

    assertTrue(r.finished);
    assertEquals("FAILED", r.buildResult);
    assertEquals("FAILED", status("deploy-region-us-east"));
    assertEquals(
        "ABORTED", status("deploy-region-eu-west"), "sibling region ABORTED by each fail-fast");
    assertEquals(
        "ABORTED",
        status("deploy-region-ap-south"),
        "other sibling region ABORTED by each fail-fast");
    assertEquals("SKIPPED", status("verify"), "Verify SKIPPED behind the failed region cell");
    assertEquals(0, stores.flowNodes().countNonTerminal(buildId));
  }

  // ════════════════════════════════════════════════════════════════════════
  // 6. ${{ env.X }} in a step argument (Fixes #1212)
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Fixes #1212: referencing {@code ${{ env.<KEY> }}} inside a step argument now resolves to the
   * merged effective env value (pipeline ← stage ← step + engine-implicit) at dispatch — the SAME
   * map that lands in {@code payload.env}. Before the fix this threw {@code unknown variable 'env'}
   * out of {@code advance()} and failed the build, because the {@code ${{ }}} resolution context
   * exposed only {@code params} / {@code steps} / {@code pipeline}, never {@code env}.
   *
   * <p>The fixture also overrides {@code REGISTRY} at the stage scope to pin precedence: the value
   * the template resolves to must be the stage value, not the pipeline value — proving the env seen
   * by {@code ${{ }}} is the effective merged env for that step, with no drift.
   */
  @Test
  void envTemplateInStepArgumentResolvesMergedEnv_Fixes1212() throws Exception {
    String yaml =
        "titan:\n"
            + "  env:\n"
            + "    REGISTRY: pipeline.example.com\n"
            + "  stages:\n"
            + "    - stage: Publish\n"
            + "      env:\n"
            + "        REGISTRY: stage.example.com\n"
            + "      steps:\n"
            + "        - sh: \"publish --registry ${{ env.REGISTRY }}\"\n";
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, yaml);
      buildId = insertBuild(c, jobId, "{}");
    }
    new TitanFlowExecution(stores, buildId).bake(yaml);

    // Capture the dispatched payload for the Publish stage's single sh node (bake names step
    // nodes <stage-slug>-s<index>), then drive to completion.
    DriveResult result =
        driveCapturing(new TitanOrchestrator(stores, buildId), Outcomes.allSuccess(), "publish-s0");

    assertTrue(
        result.finished, "build must finish — no more unknown-variable throw out of advance");
    assertEquals("SUCCESS", result.buildResult);
    assertNotNull(result.captured, "the Publish step's payload must have been captured");
    // The ${{ env.REGISTRY }} in the argument resolved to the STAGE value (stage<pipeline
    // override),
    // and it is the exact same value carried in payload.env — no drift.
    assertTrue(
        result.captured.contains("publish --registry stage.example.com"),
        "env template must resolve to the merged (stage-override) value in the command: "
            + result.captured);
    assertTrue(
        result.captured.contains("\"REGISTRY\":\"stage.example.com\""),
        "the resolved value must equal the env carried in payload.env (no drift): "
            + result.captured);
  }

  // ════════════════════════════════════════════════════════════════════════
  // Harness — bake + the stubbed-worker drive loop
  // ════════════════════════════════════════════════════════════════════════

  /** A per-task outcome: the status to complete with + the result_json to attach. */
  private record Outcome(String status, String resultJson) {}

  /** Maps a claimed task's nodeId to the Outcome the stubbed worker should return. */
  @FunctionalInterface
  private interface Outcomes extends Function<TaskQueueRow, Outcome> {
    static Outcomes allSuccess() {
      return t -> new Outcome("COMPLETED", "{\"exitCode\":0}");
    }

    static Outcomes failNodes(Set<String> failNodeIds) {
      return t ->
          t.nodeId != null && failNodeIds.contains(t.nodeId)
              ? new Outcome("FAILED", "{\"exitCode\":1}")
              : new Outcome("COMPLETED", "{\"exitCode\":0}");
    }

    static Outcomes withOutputs(String nodeId, Map<String, Object> outputs) {
      return withOutputsPerNode(Map.of(nodeId, outputs));
    }

    static Outcomes withOutputsPerNode(Map<String, Map<String, Object>> outputsByNode) {
      return t -> {
        // A control-node / gate task can carry a null nodeId; Map.of(...).get(null) throws.
        Map<String, Object> outs = t.nodeId == null ? null : outputsByNode.get(t.nodeId);
        if (outs == null) {
          return new Outcome("COMPLETED", "{\"exitCode\":0}");
        }
        StringBuilder sb = new StringBuilder("{\"exitCode\":0,\"outputs\":{");
        boolean first = true;
        for (Map.Entry<String, Object> e : outs.entrySet()) {
          if (!first) {
            sb.append(',');
          }
          first = false;
          sb.append('"').append(e.getKey()).append("\":").append(jsonLiteral(e.getValue()));
        }
        sb.append("}}");
        return new Outcome("COMPLETED", sb.toString());
      };
    }

    /** Serialise an output value as a JSON literal — booleans/numbers unquoted, strings quoted. */
    private static String jsonLiteral(Object v) {
      if (v instanceof Boolean || v instanceof Number) {
        return String.valueOf(v);
      }
      return "\"" + v + "\"";
    }
  }

  /** Result of a drive: terminal flag + build verdict + an optionally-captured payload. */
  private static final class DriveResult {
    boolean finished;
    String buildResult;
    String captured;
  }

  private DriveResult drive(Outcomes outcomes) {
    return drive(new TitanOrchestrator(stores, buildId), outcomes);
  }

  private DriveResult drive(TitanOrchestrator orch, Outcomes outcomes) {
    return driveCapturing(orch, outcomes, null);
  }

  /**
   * Loop {@code advance()} + a stubbed, queue-agnostic worker until terminal. Captures the
   * payload_json of the task whose nodeId equals {@code captureNodeId} (first dispatch wins).
   */
  private DriveResult driveCapturing(
      TitanOrchestrator orch, Outcomes outcomes, String captureNodeId) {
    DriveResult out = new DriveResult();
    for (int pass = 0; pass < 60; pass++) {
      TitanOrchestrator.AdvanceResult r = orch.advance();
      if (r.buildFinished()) {
        out.finished = true;
        out.buildResult = r.buildResult();
        return out;
      }
      for (String queue : queuesWithWork()) {
        Optional<TaskQueueRow> claimed;
        while ((claimed = stores.taskQueue().claim("stub", queue, UUID.randomUUID())).isPresent()) {
          TaskQueueRow t = claimed.get();
          if (captureNodeId != null && captureNodeId.equals(t.nodeId) && out.captured == null) {
            out.captured = t.payloadJson;
          }
          Outcome o = outcomes.apply(t);
          stores.taskQueue().complete(t.id, t.claimToken, o.status(), o.resultJson());
        }
      }
    }
    throw new AssertionError("build did not finish within 60 advance passes");
  }

  /**
   * Drive until the build finishes OR the DAG reaches a steady state (a pass that dispatches,
   * reconciles and completes nothing — i.e. it is parked on a gate). Returns silently when parked.
   */
  private void driveToSteadyState(TitanOrchestrator orch, Outcomes outcomes) {
    for (int pass = 0; pass < 60; pass++) {
      TitanOrchestrator.AdvanceResult r = orch.advance();
      if (r.buildFinished()) {
        return;
      }
      int completed = 0;
      for (String queue : queuesWithWork()) {
        Optional<TaskQueueRow> claimed;
        while ((claimed = stores.taskQueue().claim("stub", queue, UUID.randomUUID())).isPresent()) {
          TaskQueueRow t = claimed.get();
          Outcome o = outcomes.apply(t);
          stores.taskQueue().complete(t.id, t.claimToken, o.status(), o.resultJson());
          completed++;
        }
      }
      if (r.dispatched() == 0 && r.reconciled() == 0 && completed == 0) {
        return; // parked on a gate
      }
    }
    throw new AssertionError("the build neither finished nor parked within 60 passes");
  }

  private Set<String> queuesWithWork() {
    Set<String> queues = new HashSet<>();
    for (TaskQueueRow t : stores.taskQueue().listByBuild(buildId)) {
      if ("QUEUED".equals(t.status) && t.queueName != null) {
        queues.add(t.queueName);
      }
    }
    if (queues.isEmpty()) {
      queues.add("default");
    }
    return queues;
  }

  // ── bake helpers ──────────────────────────────────────────────────────────

  private void bake(String fixture) throws Exception {
    bake(fixture, "{}");
  }

  private void bake(String fixture, String parametersJson) throws Exception {
    try (Connection c = ds.getConnection()) {
      long jobId = insertJob(c, Fixtures.load(fixture));
      buildId = insertBuild(c, jobId, parametersJson);
    }
    new TitanFlowExecution(stores, buildId).bake(Fixtures.load(fixture));
  }

  private Map<String, FlowNodeRow> nodesById() {
    return stores.flowNodes().listByBuild(buildId).stream()
        .collect(Collectors.toMap(n -> n.nodeId, n -> n));
  }

  private String status(String nodeId) {
    return stores.flowNodes().findByBuildAndNode(buildId, nodeId).orElseThrow().status;
  }

  private static long insertJob(Connection c, String yaml) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.jobs (full_name, pipeline_script, config_json) VALUES (?, ?, '{}')",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, "complex/job-" + System.nanoTime());
      ps.setString(2, yaml);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private static long insertBuild(Connection c, long jobId, String parametersJson)
      throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO titan.builds (job_id, build_number, status, parameters_json) "
                + "VALUES (?, 1, 'QUEUED', ?)",
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setLong(1, jobId);
      ps.setString(2, parametersJson);
      ps.executeUpdate();
      try (ResultSet keys = ps.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }
}
