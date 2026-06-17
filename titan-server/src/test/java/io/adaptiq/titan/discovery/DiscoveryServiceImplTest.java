package io.adaptiq.titan.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.job.JobNotFoundException;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link DiscoveryServiceImpl} against an H2 {@link TitanStores}, with a stub
 * {@link GitHeadResolver} so no network is touched. Asserts the core contract:
 *
 * <ul>
 *   <li>new (or unseen) SHA triggers a build with {@code triggerType="discovery"} and stamps {@code
 *       last_seen_sha} in {@code titan.discovery_state};
 *   <li>unchanged SHA is a no-op — no build row created, {@code last_polled_at} still advances;
 *   <li>jobs with no {@code scm} block are skipped;
 *   <li>a resolver failure absorbs cleanly, does not trigger, records {@code last_status="failed"}
 *       and does not overwrite an existing {@code last_seen_sha};
 *   <li>{@link DiscoveryService#pollOne(long)} surfaces {@link JobNotFoundException}.
 * </ul>
 */
class DiscoveryServiceImplTest {

  private TitanStores stores;
  private StubGitHead head;
  private DiscoveryServiceImpl svc;

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
    head = new StubGitHead();
    svc = new DiscoveryServiceImpl(stores, head);
  }

  // ── happy path: new SHA → build triggered, state stamped ───────────────────

  @Test
  void newSha_triggersBuild() {
    long jobId = insertJob("acme/billing-" + System.nanoTime(), scmJson("https://x", "main"));
    head.put("https://x", "main", "aaaa1111");

    Optional<Long> buildId = svc.pollOne(jobId);
    assertTrue(buildId.isPresent(), "build must be triggered on first poll");

    var builds = stores.builds().listByJob(jobId);
    assertEquals(1, builds.size(), "exactly one build inserted");
    assertEquals("discovery", builds.get(0).triggerType, "triggerType must be 'discovery'");
    assertEquals("QUEUED", builds.get(0).status);

    // ORCHESTRATE task enqueued in the same transaction
    var tasks = stores.taskQueue().listByBuild(buildId.get());
    assertTrue(tasks.stream().anyMatch(t -> "ORCHESTRATE".equals(t.type)));

    // State row written
    var state = stores.discoveryState().findByJobId(jobId).orElseThrow();
    assertEquals("aaaa1111", state.lastSeenSha);
    assertEquals("ok", state.lastStatus);
    assertNotNull(state.lastPolledAt);
  }

  // ── unchanged SHA: no-op, state still stamped ──────────────────────────────

  @Test
  void unchangedSha_isNoOp() {
    long jobId = insertJob("acme/idle-" + System.nanoTime(), scmJson("https://y", "main"));
    head.put("https://y", "main", "bbbb2222");

    // First poll → triggers a build, stamps the state.
    assertTrue(svc.pollOne(jobId).isPresent());

    // Second poll with the SAME SHA → no new build.
    Optional<Long> second = svc.pollOne(jobId);
    assertFalse(second.isPresent(), "unchanged SHA must NOT trigger a build");

    assertEquals(1, stores.builds().listByJob(jobId).size(), "still exactly one build");
    var state = stores.discoveryState().findByJobId(jobId).orElseThrow();
    assertEquals("bbbb2222", state.lastSeenSha);
    assertEquals("ok", state.lastStatus);
  }

  // ── second changed SHA: triggers another build ─────────────────────────────

  @Test
  void changedSha_triggersAnotherBuild() {
    long jobId = insertJob("acme/active-" + System.nanoTime(), scmJson("https://z", "main"));
    head.put("https://z", "main", "first111");
    svc.pollOne(jobId);

    head.put("https://z", "main", "second22");
    Optional<Long> second = svc.pollOne(jobId);
    assertTrue(second.isPresent(), "changed SHA must trigger a second build");

    assertEquals(2, stores.builds().listByJob(jobId).size());
    assertEquals("second22", stores.discoveryState().findByJobId(jobId).orElseThrow().lastSeenSha);
  }

  // ── no scm block → silently skipped ────────────────────────────────────────

  @Test
  void jobWithoutScmBlock_isSkipped() {
    long jobId = insertJob("acme/no-scm-" + System.nanoTime(), "{}");
    Optional<Long> r = svc.pollOne(jobId);
    assertFalse(r.isPresent());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
    assertTrue(stores.discoveryState().findByJobId(jobId).isEmpty());
  }

  // ── resolver failure: absorbed, no trigger, state recorded as failed ───────

  @Test
  void resolverFailure_isAbsorbed_andDoesNotClobberLastSeen() {
    long jobId = insertJob("acme/flaky-" + System.nanoTime(), scmJson("https://f", "main"));

    // First a successful poll lands a baseline SHA.
    head.put("https://f", "main", "good1234");
    svc.pollOne(jobId);
    assertEquals("good1234", stores.discoveryState().findByJobId(jobId).orElseThrow().lastSeenSha);

    // Next poll fails.
    head.fail("https://f", "main", "boom");
    Optional<Long> result = svc.pollOne(jobId);
    assertFalse(result.isPresent(), "failure must not trigger a build");

    var state = stores.discoveryState().findByJobId(jobId).orElseThrow();
    assertEquals("failed", state.lastStatus);
    assertEquals("boom", state.lastError);
    // last_seen_sha must NOT be overwritten — otherwise a recovery would re-trigger.
    assertEquals("good1234", state.lastSeenSha, "last_seen_sha must survive a transient failure");
  }

  // ── pollOne on missing job → JobNotFoundException ──────────────────────────

  @Test
  void pollOne_missingJob_throws() {
    assertThrows(JobNotFoundException.class, () -> svc.pollOne(999_999L));
  }

  // ── pollAll iterates every enabled job ─────────────────────────────────────

  @Test
  void pollAll_triggersOnePerChangedJob() {
    long a = insertJob("a/" + System.nanoTime(), scmJson("https://a", "main"));
    long b = insertJob("b/" + System.nanoTime(), scmJson("https://b", "main"));
    long noScm = insertJob("c/" + System.nanoTime(), "{}");
    head.put("https://a", "main", "AAAA");
    head.put("https://b", "main", "BBBB");

    int triggered = svc.pollAll();
    assertEquals(2, triggered);

    assertEquals(1, stores.builds().listByJob(a).size());
    assertEquals(1, stores.builds().listByJob(b).size());
    assertTrue(stores.builds().listByJob(noScm).isEmpty());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private long insertJob(String fullName, String configJson) {
    JobRow r = new JobRow();
    r.fullName = fullName;
    r.enabled = true;
    r.pipelineScript = "";
    r.configJson = configJson;
    return stores.jobs().insert(r);
  }

  private static String scmJson(String url, String branch) {
    return "{\"scm\":{\"url\":\"" + url + "\",\"branch\":\"" + branch + "\"}}";
  }

  /** Test-double resolver: registered (url, branch) → SHA, or a configured failure. */
  static final class StubGitHead implements GitHeadResolver {
    private final Map<String, String> shas = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();

    void put(String url, String branch, String sha) {
      shas.put(key(url, branch), sha);
      failures.remove(key(url, branch));
    }

    void fail(String url, String branch, String msg) {
      failures.put(key(url, branch), msg);
      shas.remove(key(url, branch));
    }

    @Override
    public String resolve(String url, String branch) throws GitHeadException {
      String k = key(url, branch);
      if (failures.containsKey(k)) {
        throw new GitHeadException(failures.get(k));
      }
      String sha = shas.get(k);
      if (sha == null) {
        throw new GitHeadException("no stub registered for " + k);
      }
      return sha;
    }

    private static String key(String url, String branch) {
      return url + "##" + branch;
    }
  }
}
