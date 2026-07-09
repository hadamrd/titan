package io.adaptiq.titan.api.triggers.bitbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BitbucketWebhookApi} — exercises HMAC-SHA256 verification, the {@code
 * X-Event-Key} discriminated-union dispatch, the {@code pullrequest:approved} ignored path, the
 * adversarial malformed-JSON path, and the security invariant that the HMAC secret is NEVER logged
 * (issue #1079).
 *
 * <p>Strategy mirrors {@code GitlabWebhookApiTest}: plain JUnit, package-private SUT constructor,
 * H2-backed {@link TitanStores} via the package-private {@code FakeTitanStores} helper (reached by
 * reflection so we don't widen its public surface).
 */
class BitbucketWebhookApiTest {

  private static final String SECRET = "test-bitbucket-secret-DO-NOT-LEAK";

  private TitanStores stores;
  private InMemoryJobService jobs;
  private InMemoryCredentials creds;
  private BitbucketWebhookApi api;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);

    jobs = new InMemoryJobService();
    creds = new InMemoryCredentials();

    var ctor =
        BitbucketWebhookApi.class.getDeclaredConstructor(
            TitanStores.class, JobService.class, CredentialsService.class);
    ctor.setAccessible(true);
    api = (BitbucketWebhookApi) ctor.newInstance(stores, jobs, creds);
  }

  // ── 1. valid HMAC + repo:push to matching branch → build enqueued ────────────

  @Test
  void validHmac_matchingBranch_repoPush_enqueuesBuildWithRefAndSha() {
    long jobId = seedJob("team/repo-a", "trunk", "push");
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    byte[] body = pushBody("trunk", "deadbeefcafedeadbeefcafedeadbeefcafe1234");
    Response resp = api.receive(headers(sign(SECRET, body), "repo:push"), body);

    assertEquals(200, resp.getStatus());
    var built = stores.builds().listByJob(jobId);
    assertEquals(1, built.size(), "exactly one build should be enqueued");
    assertEquals("bitbucket", built.get(0).triggerType);
    String meta = built.get(0).triggerMetaJson;
    assertTrue(meta != null && meta.contains("\"branch\":\"trunk\""), "metadata must carry branch");
    assertTrue(
        meta.contains("\"commitSha\":\"deadbeefcafedeadbeefcafedeadbeefcafe1234\""),
        "metadata must carry the FULL commit hash, not truncated");
  }

  // ── 2. HMAC mismatch → 401, structured body, no leak ─────────────────────────

  @Test
  void invalidHmac_returns401_withStructuredReason_andNoLeak() {
    long jobId = seedJob("team/repo-b", "trunk", "push");
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    byte[] body = pushBody("trunk", "abc");
    // Sign with the wrong secret.
    Response resp = api.receive(headers(sign("not-the-secret", body), "repo:push"), body);

    assertEquals(401, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
    assertEquals("application/problem+json", resp.getMediaType().toString());
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals(Boolean.FALSE, entity.get("ok"));
    assertEquals("invalid_signature", entity.get("reason"));
    // SECURITY: the secret must NEVER appear in the response body.
    assertFalse(entity.toString().contains(SECRET), "401 body leaked the secret");
  }

  // ── 3. pullrequest:created → build on PR head branch ─────────────────────────

  @Test
  void pullRequestCreated_enqueuesBuildOnSourceBranch() {
    long jobId = seedJob("team/repo-c", "feat/x", "pull_request");
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    byte[] body = pullRequestBody("feat/x", "prsha123", 42);
    Response resp = api.receive(headers(sign(SECRET, body), "pullrequest:created"), body);

    assertEquals(200, resp.getStatus());
    var built = stores.builds().listByJob(jobId);
    assertEquals(1, built.size());
    String meta = built.get(0).triggerMetaJson;
    assertTrue(meta.contains("\"branch\":\"feat/x\""), "meta must carry PR head branch: " + meta);
    assertTrue(meta.contains("\"prId\":42"), "meta must carry the PR id: " + meta);
  }

  @Test
  void pullRequestUpdated_enqueuesBuild() {
    long jobId = seedJob("team/repo-c2", "feat/y", "pull_request");
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    byte[] body = pullRequestBody("feat/y", "prsha456", 7);
    Response resp = api.receive(headers(sign(SECRET, body), "pullrequest:updated"), body);

    assertEquals(200, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());
  }

  // ── 4. pullrequest:approved → ignored (we only fire on created/updated) ──────

  @Test
  void pullRequestApproved_returns200Ignored_noBuild() {
    long jobId = seedJob("team/repo-d", "feat/x", "pull_request");
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    byte[] body = pullRequestBody("feat/x", "prsha", 1);
    Response resp = api.receive(headers(sign(SECRET, body), "pullrequest:approved"), body);

    assertEquals(200, resp.getStatus());
    assertTrue(
        stores.builds().listByJob(jobId).isEmpty(), "approved action must not enqueue a build");
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals("pullrequest:approved", entity.get("ignored"));
  }

  // ── 5. unknown event key → 200 ignored ───────────────────────────────────────

  @Test
  void unknownEvent_returns200Ignored_noBuild() {
    long jobId = seedJob("team/repo-e", "trunk", "push");
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    Response resp =
        api.receive(headers(sign(SECRET, "{}".getBytes()), "repo:fork"), "{}".getBytes());

    assertEquals(200, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 6. adversarial: malformed JSON body → 400 + structured error, no NPE ─────

  @Test
  void malformedJson_returns400_structuredErrorNoNpe() {
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
    Response resp = api.receive(headers(sign(SECRET, body), "repo:push"), body);

    assertEquals(400, resp.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals("malformed_json", entity.get("reason"));
  }

  // ── 7. missing signature / event headers ─────────────────────────────────────

  @Test
  void missingSignatureHeader_returns401() {
    Response resp = api.receive(headers(null, "repo:push"), "{}".getBytes());
    assertEquals(401, resp.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals("missing_signature", entity.get("reason"));
  }

  @Test
  void missingEventHeader_returns400() {
    Response resp = api.receive(headers(sign(SECRET, "{}".getBytes()), null), "{}".getBytes());
    assertEquals(400, resp.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals("missing_event_header", entity.get("reason"));
  }

  // ── 8. wrong event configured on trigger → no build (push-only job, PR event) ─

  @Test
  void prEvent_butJobOnlyAcceptsPush_noBuild() {
    long jobId = seedJob("team/repo-f", "feat/x", "push");
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    byte[] body = pullRequestBody("feat/x", "sha", 3);
    Response resp = api.receive(headers(sign(SECRET, body), "pullrequest:created"), body);

    assertEquals(200, resp.getStatus());
    assertTrue(
        stores.builds().listByJob(jobId).isEmpty(),
        "a push-only trigger must not fire on a PR event");
  }

  // ── 9. SECURITY — secret NEVER appears in log output ─────────────────────────

  @Test
  void invalidHmac_secretNeverWrittenToLogs() {
    seedJob("team/repo-sec", "trunk", "push");
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    Logger root = Logger.getLogger("io.adaptiq.titan.api.triggers.bitbucket");
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    Handler capture = new CapturingHandler(buf);
    Level original = root.getLevel();
    boolean origUseParent = root.getUseParentHandlers();
    root.addHandler(capture);
    root.setLevel(Level.ALL);
    root.setUseParentHandlers(false);
    try {
      byte[] body = pushBody("trunk", "abc");
      api.receive(headers(sign("wrong-secret", body), "repo:push"), body);
    } finally {
      root.removeHandler(capture);
      root.setLevel(original);
      root.setUseParentHandlers(origUseParent);
    }

    String logs = buf.toString(StandardCharsets.UTF_8);
    assertFalse(logs.contains(SECRET), "logs leaked the secret: " + logs);
  }

  // ── 10. HMAC helper unit + ref matcher smoke ─────────────────────────────────

  @Test
  void verifyHmac_trueForCorrectSignature_falseForWrong() {
    byte[] body = "{\"x\":1}".getBytes(StandardCharsets.UTF_8);
    assertTrue(BitbucketWebhookApi.verifyHmac(SECRET, body, sign(SECRET, body)));
    assertFalse(BitbucketWebhookApi.verifyHmac(SECRET, body, sign("other", body)));
    assertFalse(BitbucketWebhookApi.verifyHmac(SECRET, body, "not-a-sha256-header"));
    assertFalse(BitbucketWebhookApi.verifyHmac(SECRET, body, "sha256=zz"));
  }

  @Test
  void refMatcher_emptyPatternsMatchesAny() {
    assertTrue(BitbucketWebhookApi.refMatches(List.of(), "anything"));
  }

  @Test
  void refMatcher_doubleStarPrefix() {
    assertTrue(BitbucketWebhookApi.refMatches(List.of("feat/**"), "feat/x"));
    assertFalse(BitbucketWebhookApi.refMatches(List.of("feat/**"), "trunk"));
  }

  @Test
  void refMatcher_singleStarGlob() {
    // 'release-*' is advertised by the grammar docs; the prefix-only matcher silently dropped it.
    assertTrue(BitbucketWebhookApi.refMatches(List.of("release-*"), "release-1.0"));
    assertFalse(BitbucketWebhookApi.refMatches(List.of("release-*"), "trunk"));
    // mid-pattern star must stay anchored at both ends
    assertTrue(BitbucketWebhookApi.refMatches(List.of("v*-rc"), "v2-rc"));
    assertFalse(BitbucketWebhookApi.refMatches(List.of("v*-rc"), "v2-final"));
  }

  // ── #69: enqueue failure must NEVER be swallowed into a 2xx ─────────────────

  @Test
  void enqueueFailure_returns500_andLogsSevere() {
    // Job known to the JobService but with no titan.jobs row — allocation inside enqueueBuild
    // throws. Mirrors GithubWebhookApiTest; a 2xx would silently drop the event.
    String configJson =
        "{\"triggers\":[{\"type\":\"bitbucket\",\"id\":\"trig-1\",\"branches\":[\"trunk\"],"
            + "\"events\":[\"push\"],\"credentialsId\":\"bb-secret\"}]}";
    jobs.add(
        new Job(999_999L, "team/repo-phantom", null, null, "", configJson, null, null, null, true));
    creds.put("bitbucket-webhook", "bb-secret", SECRET);

    List<LogRecord> records = new java.util.ArrayList<>();
    Logger logger = Logger.getLogger(BitbucketWebhookApi.class.getName());
    Handler capture = recordingHandler(records);
    logger.addHandler(capture);
    Response resp;
    try {
      byte[] body = pushBody("trunk", "deadbeefcafedeadbeefcafedeadbeefcafe1234");
      resp = api.receive(headers(sign(SECRET, body), "repo:push"), body);
    } finally {
      logger.removeHandler(capture);
    }

    assertEquals(
        500,
        resp.getStatus(),
        "enqueue failure must surface as non-2xx so the sender retries (#69)");
    assertTrue(
        records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE && r.getThrown() != null),
        "enqueue failure must be logged at SEVERE with the cause chain (#69)");
  }

  @NonNull
  private static Handler recordingHandler(@NonNull List<LogRecord> sink) {
    return new Handler() {
      @Override
      public void publish(LogRecord record) {
        sink.add(record);
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private long seedJob(@NonNull String fullName, @NonNull String ref, @NonNull String eventName) {
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson =
        "{\"triggers\":[{\"type\":\"bitbucket\","
            + "\"id\":\"trig-1\","
            + "\"branches\":[\""
            + ref
            + "\"],"
            + "\"events\":[\""
            + eventName
            + "\"],"
            + "\"credentialsId\":\"bb-secret\"}]}";
    long jobId = stores.jobs().insert(row);
    jobs.add(new Job(jobId, fullName, null, null, "", row.configJson, null, null, null, true));
    return jobId;
  }

  private static byte[] pushBody(@NonNull String branch, @NonNull String sha) {
    return ("{\"push\":{\"changes\":[{\"new\":{\"type\":\"branch\",\"name\":\""
            + branch
            + "\",\"target\":{\"hash\":\""
            + sha
            + "\"}}}]},"
            + "\"actor\":{\"display_name\":\"Alice\"},"
            + "\"repository\":{\"full_name\":\"team/repo\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] pullRequestBody(
      @NonNull String sourceBranch, @NonNull String headSha, long prId) {
    return ("{\"pullrequest\":{\"id\":"
            + prId
            + ",\"source\":{\"branch\":{\"name\":\""
            + sourceBranch
            + "\"},\"commit\":{\"hash\":\""
            + headSha
            + "\"}}},"
            + "\"actor\":{\"display_name\":\"Bob\"},"
            + "\"repository\":{\"full_name\":\"team/repo\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  /** Produce a {@code X-Hub-Signature} value (the {@code sha256=…} hex form) over {@code body}. */
  @NonNull
  private static String sign(@NonNull String secret, @NonNull byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder("sha256=");
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @NonNull
  private static HttpHeaders headers(String signature, String eventKey) {
    Map<String, String> map = new HashMap<>();
    if (signature != null) {
      map.put("X-Hub-Signature", signature);
    }
    if (eventKey != null) {
      map.put("X-Event-Key", eventKey);
    }
    return new StubHeaders(map);
  }

  // ── stubs ────────────────────────────────────────────────────────────────────

  static final class InMemoryJobService implements JobService {
    private final List<Job> all = new java.util.ArrayList<>();

    void add(@NonNull Job job) {
      all.add(job);
    }

    @Override
    @NonNull
    public Optional<Job> findById(long id) {
      return all.stream().filter(j -> j.id() == id).findFirst();
    }

    @Override
    @NonNull
    public Optional<Job> findByFullName(@NonNull String fullName) {
      return all.stream().filter(j -> fullName.equals(j.fullName())).findFirst();
    }

    @Override
    @NonNull
    public List<Job> listAll() {
      return List.copyOf(all);
    }

    @Override
    @NonNull
    public List<io.adaptiq.titan.job.JobWithLastBuild> listAllWithLastBuild() {
      return all.stream().map(j -> new io.adaptiq.titan.job.JobWithLastBuild(j, null)).toList();
    }

    @Override
    public void delete(long id) {
      all.removeIf(j -> j.id() == id);
    }

    @Override
    @NonNull
    public Job create(@NonNull io.adaptiq.titan.job.NewJobRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Job update(long id, @NonNull io.adaptiq.titan.job.JobUpdate update) {
      throw new UnsupportedOperationException();
    }
  }

  static final class InMemoryCredentials implements CredentialsService {
    private final Map<String, String> store = new HashMap<>();

    void put(@NonNull String scope, @NonNull String key, @NonNull String value) {
      store.put(scope + "/" + key, value);
    }

    @Override
    @NonNull
    public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
      return Optional.ofNullable(store.get(scope + "/" + key));
    }

    @Override
    @NonNull
    public Optional<Credential> findById(long id) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Optional<Credential> findByScopeAndKey(@NonNull String scope, @NonNull String key) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public List<Credential> listAll() {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public List<Credential> listByScope(@NonNull String scope) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Credential create(@NonNull NewCredentialRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Credential update(long id, @NonNull CredentialUpdate update) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(long id) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public String backendName() {
      return "in-memory-test";
    }

    @Override
    public int rotateKek() {
      return 0;
    }
  }

  static final class StubHeaders implements HttpHeaders {
    private final Map<String, String> headers;

    StubHeaders(@NonNull Map<String, String> headers) {
      this.headers = headers;
    }

    @Override
    public String getHeaderString(String name) {
      for (var e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue();
        }
      }
      return null;
    }

    @Override
    public List<String> getRequestHeader(String name) {
      String v = getHeaderString(name);
      return v == null ? List.of() : List.of(v);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
      MultivaluedMap<String, String> out = new MultivaluedHashMap<>();
      headers.forEach(out::add);
      return out;
    }

    @Override
    public List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
      return List.of();
    }

    @Override
    public List<java.util.Locale> getAcceptableLanguages() {
      return List.of();
    }

    @Override
    public jakarta.ws.rs.core.MediaType getMediaType() {
      return null;
    }

    @Override
    public java.util.Locale getLanguage() {
      return null;
    }

    @Override
    public Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
      return Map.of();
    }

    @Override
    public java.util.Date getDate() {
      return null;
    }

    @Override
    public int getLength() {
      return -1;
    }
  }

  /** j.u.l handler that captures every formatted message to a buffer for assertion. */
  static final class CapturingHandler extends Handler {
    private final PrintStream ps;

    CapturingHandler(@NonNull ByteArrayOutputStream buf) {
      this.ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
      setLevel(Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
      ps.println(record.getMessage());
      Object[] params = record.getParameters();
      if (params != null) {
        for (Object p : params) {
          ps.println(p == null ? "" : p.toString());
        }
      }
      if (record.getThrown() != null) {
        record.getThrown().printStackTrace(ps);
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
