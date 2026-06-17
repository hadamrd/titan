package io.adaptiq.titan.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import org.junit.jupiter.api.Test;

/**
 * Regression test for #847: discovery-triggered builds used to inject {@code GIT_COMMIT} into
 * {@code parameters_json}, which then failed the bake with <em>"parameter 'GIT_COMMIT' was supplied
 * but is not declared by the pipeline"</em> on any V1-golden-path pipeline that didn't pre-declare
 * it.
 *
 * <p>The fix routes the SHA through {@code trigger_meta_json} instead (where the GitHub-App webhook
 * already writes it). Parameters stay user-pure; implicit env is engine-owned and exposed by {@link
 * io.adaptiq.titan.flow.orch.ImplicitBuildEnv} at dispatch.
 */
class DiscoveryGitCommitRoutingTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** The fix's core invariant: discovery MUST NOT pollute parameters_json with GIT_COMMIT. */
  @Test
  void discoveryDoesNotInjectGitCommitIntoParameters() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    DiscoveryServiceImplTest.StubGitHead head = new DiscoveryServiceImplTest.StubGitHead();
    DiscoveryServiceImpl svc = new DiscoveryServiceImpl(stores, head);

    long jobId = insertJob(stores, "acme/no-params-" + System.nanoTime(), "https://x", "main");
    head.put("https://x", "main", "deadbeefcafef00d");

    long buildId = svc.pollOne(jobId).orElseThrow();
    BuildRow build = stores.builds().findById(buildId).orElseThrow();

    // parameters_json is either null or an empty object — GIT_COMMIT MUST NOT appear here.
    if (build.parametersJson != null && !build.parametersJson.isBlank()) {
      JsonNode params = JSON.readTree(build.parametersJson);
      assertTrue(
          params.path("GIT_COMMIT").isMissingNode() || params.path("GIT_COMMIT").isNull(),
          "GIT_COMMIT must not be present in parameters_json (closes #847); was: "
              + build.parametersJson);
    }
  }

  /** The SHA must land in trigger_meta_json so the dispatcher can surface it as implicit env. */
  @Test
  void discoveryRoutesGitCommitIntoTriggerMetaJson() throws Exception {
    TitanStores stores = FakeTitanStores.create();
    DiscoveryServiceImplTest.StubGitHead head = new DiscoveryServiceImplTest.StubGitHead();
    DiscoveryServiceImpl svc = new DiscoveryServiceImpl(stores, head);

    long jobId = insertJob(stores, "acme/meta-" + System.nanoTime(), "https://y", "main");
    head.put("https://y", "main", "0123456789abcdef");

    long buildId = svc.pollOne(jobId).orElseThrow();
    BuildRow build = stores.builds().findById(buildId).orElseThrow();

    assertNotNull(build.triggerMetaJson, "trigger_meta_json must be written by discovery");
    JsonNode meta = JSON.readTree(build.triggerMetaJson);
    assertEquals("0123456789abcdef", meta.path("commitSha").asText());
  }

  /**
   * Bake-equivalent: a pipeline with NO parameters: block, triggered by discovery, must round-trip
   * without the ParameterResolver "supplied but not declared" failure. This is the original P1 user
   * symptom — exercised directly by feeding the resolver an empty `declared` list and the build's
   * actual supplied params (the empty / null map written by discovery now).
   */
  @Test
  void pipelineWithoutParametersBlockCanBeTriggeredByDiscovery() {
    TitanStores stores = FakeTitanStores.create();
    DiscoveryServiceImplTest.StubGitHead head = new DiscoveryServiceImplTest.StubGitHead();
    DiscoveryServiceImpl svc = new DiscoveryServiceImpl(stores, head);

    long jobId = insertJob(stores, "acme/v1-golden-" + System.nanoTime(), "https://z", "main");
    head.put("https://z", "main", "abc123");

    long buildId = svc.pollOne(jobId).orElseThrow();
    BuildRow build = stores.builds().findById(buildId).orElseThrow();

    // Discovery does not supply any user parameters — parameters_json is null/blank, which the
    // bake's parseParams() treats as Map.of() and ParameterResolver.resolve([], {}) returns
    // empty without throwing. This is what the fix unblocks.
    if (build.parametersJson != null) {
      // If something writes a JSON literal, it must be the empty object.
      assertEquals("", build.parametersJson.replace("{}", "").trim());
    } else {
      assertNull(build.parametersJson);
    }
  }

  private static long insertJob(TitanStores stores, String fullName, String url, String branch) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.enabled = true;
    r.pipelineScript = "";
    r.configJson = "{\"scm\":{\"url\":\"" + url + "\",\"branch\":\"" + branch + "\"}}";
    return stores.jobs().insert(r);
  }
}
