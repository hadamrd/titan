package io.adaptiq.titan.api.triggers.gitlab;

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
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GitlabWebhookApi} — exercises shared-secret verification, the
 * X-Gitlab-Event discriminated-union dispatch, MR-action filtering, the adversarial
 * missing-object_kind path, and the security invariant that the expected token is NEVER logged
 * (issue #1078).
 *
 * <p>Strategy mirrors {@code GithubWebhookApiTest}: plain JUnit, package-private SUT constructor,
 * H2-backed {@link TitanStores} via the package-private {@code FakeTitanStores} helper (reached by
 * reflection so we don't widen its public surface).
 */
class GitlabWebhookApiTest {

  private static final String TOKEN = "test-gitlab-token-DO-NOT-LEAK";

  private TitanStores stores;
  private InMemoryJobService jobs;
  private InMemoryCredentials creds;
  private GitlabWebhookApi api;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);

    jobs = new InMemoryJobService();
    creds = new InMemoryCredentials();

    var ctor =
        GitlabWebhookApi.class.getDeclaredConstructor(
            TitanStores.class, JobService.class, CredentialsService.class);
    ctor.setAccessible(true);
    api = (GitlabWebhookApi) ctor.newInstance(stores, jobs, creds);
  }

  // ── 1. valid token + push to matching branch → build enqueued ────────────────

  @Test
  void validToken_matchingBranch_pushEvent_enqueuesBuildWithRefAndSha() {
    long jobId = seedJob("group/repo-a", "trunk", "push");
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    byte[] body = pushBody("refs/heads/trunk", "deadbeefcafedeadbeefcafedeadbeefcafe1234");
    Response resp = api.receive(headers(TOKEN, "Push Hook"), body);

    assertEquals(200, resp.getStatus());
    var built = stores.builds().listByJob(jobId);
    assertEquals(1, built.size(), "exactly one build should be enqueued");
    assertEquals("gitlab", built.get(0).triggerType);
    String meta = built.get(0).triggerMetaJson;
    assertTrue(meta != null && meta.contains("\"branch\":\"trunk\""), "metadata must carry branch");
    assertTrue(
        meta.contains("\"commitSha\":\"deadbeefcafedeadbeefcafedeadbeefcafe1234\""),
        "metadata must carry the FULL 40-char SHA, not truncated");
  }

  // ── 1b. trigger metadata carries projectId + projectPath + gitlabCredentialsId (#1080) ────

  @Test
  void pushEvent_triggerMeta_includesProjectIdAndCredentialsId() {
    long jobId = seedJob("group/repo-meta", "trunk", "push");
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    String body =
        "{\"object_kind\":\"push\","
            + "\"ref\":\"refs/heads/trunk\","
            + "\"checkout_sha\":\"deadbeefcafedeadbeefcafedeadbeefcafe1234\","
            + "\"user_username\":\"alice\","
            + "\"project\":{\"id\":98765,\"path_with_namespace\":\"group/repo-meta\"}}";
    Response resp = api.receive(headers(TOKEN, "Push Hook"), body.getBytes(StandardCharsets.UTF_8));

    assertEquals(200, resp.getStatus());
    var built = stores.builds().listByJob(jobId);
    assertEquals(1, built.size());
    String meta = built.get(0).triggerMetaJson;
    assertTrue(
        meta != null && meta.contains("\"projectId\":98765"), "meta missing projectId: " + meta);
    assertTrue(
        meta.contains("\"projectPath\":\"group/repo-meta\""), "meta missing projectPath: " + meta);
    assertTrue(
        meta.contains("\"gitlabCredentialsId\":\"gl-token\""),
        "meta missing gitlabCredentialsId: " + meta);
  }

  // ── 2. invalid token → 401, structured body, expected token NOT in body ──────

  @Test
  void invalidToken_returns401_withStructuredReason_andNoLeak() {
    long jobId = seedJob("group/repo-b", "trunk", "push");
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    byte[] body = pushBody("refs/heads/trunk", "abc");
    Response resp = api.receive(headers("not-the-right-token", "Push Hook"), body);

    assertEquals(401, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
    assertEquals("application/problem+json", resp.getMediaType().toString());
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals(Boolean.FALSE, entity.get("ok"));
    assertEquals("invalid_token", entity.get("reason"));
    // SECURITY: the expected token must NEVER appear in the response body.
    assertFalse(entity.toString().contains(TOKEN), "401 body leaked the expected token");
  }

  // ── 3. MR action=close → no build (only open/sync) ───────────────────────────

  @Test
  void mergeRequest_closeAction_noBuild() {
    long jobId = seedJob("group/repo-c", "feat/x", "merge_request");
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    byte[] body = mergeRequestBody("close", "feat/x", "sha-1");
    Response resp = api.receive(headers(TOKEN, "Merge Request Hook"), body);

    assertEquals(200, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty(), "close action must not enqueue");
  }

  @Test
  void mergeRequest_openAction_enqueuesBuildOnSourceBranch() {
    long jobId = seedJob("group/repo-c2", "feat/x", "merge_request");
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    byte[] body = mergeRequestBody("open", "feat/x", "sha-open");
    Response resp = api.receive(headers(TOKEN, "Merge Request Hook"), body);

    assertEquals(200, resp.getStatus());
    var built = stores.builds().listByJob(jobId);
    assertEquals(1, built.size());
    assertTrue(built.get(0).triggerMetaJson.contains("\"branch\":\"feat/x\""));
  }

  // ── 4. tag push → build ──────────────────────────────────────────────────────

  @Test
  void tagPush_enqueuesBuildWithTagAsRef() {
    long jobId = seedJob("group/repo-d", "v**", "tag_push");
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    byte[] body = tagPushBody("refs/tags/v1.2.3", "tagsha");
    Response resp = api.receive(headers(TOKEN, "Tag Push Hook"), body);

    assertEquals(200, resp.getStatus());
    var built = stores.builds().listByJob(jobId);
    assertEquals(1, built.size(), "tag push on matching pattern should enqueue");
  }

  // ── 5. unknown event → 200 ignored ───────────────────────────────────────────

  @Test
  void unknownEvent_returns200Ignored_noBuild() {
    long jobId = seedJob("group/repo-e", "trunk", "push");
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    Response resp =
        api.receive(headers(TOKEN, "Wiki Page Hook"), "{\"object_kind\":\"wiki_page\"}".getBytes());

    assertEquals(200, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 6. adversarial: missing object_kind → 400 + structured error, no NPE ─────

  @Test
  void missingObjectKind_returns400_structuredErrorNoNpe() {
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    // Body looks push-shaped but lacks the discriminator field entirely.
    byte[] body =
        "{\"ref\":\"refs/heads/trunk\",\"after\":\"abc\"}".getBytes(StandardCharsets.UTF_8);
    Response resp = api.receive(headers(TOKEN, "Push Hook"), body);

    assertEquals(400, resp.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals("missing_object_kind", entity.get("reason"));
  }

  // ── 7. missing token / event headers ─────────────────────────────────────────

  @Test
  void missingTokenHeader_returns401() {
    Response resp = api.receive(headers(null, "Push Hook"), "{}".getBytes());
    assertEquals(401, resp.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals("missing_token", entity.get("reason"));
  }

  @Test
  void missingEventHeader_returns400() {
    creds.put("gitlab-webhook", "gl-token", TOKEN);
    Response resp = api.receive(headers(TOKEN, null), "{}".getBytes());
    assertEquals(400, resp.getStatus());
    @SuppressWarnings("unchecked")
    Map<String, Object> entity = (Map<String, Object>) resp.getEntity();
    assertEquals("missing_event_header", entity.get("reason"));
  }

  @Test
  void malformedJson_returns400() {
    creds.put("gitlab-webhook", "gl-token", TOKEN);
    Response resp = api.receive(headers(TOKEN, "Push Hook"), "not-json".getBytes());
    assertEquals(400, resp.getStatus());
  }

  // ── 8. SECURITY — expected token NEVER appears in log output ─────────────────

  @Test
  void invalidToken_expectedSecretNeverWrittenToLogs() throws Exception {
    seedJob("group/repo-sec", "trunk", "push");
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    // Capture the j.u.l output produced by GitlabWebhookApi during a deliberately-failing call.
    Logger root = Logger.getLogger("io.adaptiq.titan.api.triggers.gitlab");
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    Handler capture = new CapturingHandler(buf);
    Level original = root.getLevel();
    boolean origUseParent = root.getUseParentHandlers();
    root.addHandler(capture);
    root.setLevel(Level.ALL);
    root.setUseParentHandlers(false);
    try {
      byte[] body = pushBody("refs/heads/trunk", "abc");
      api.receive(headers("wrong-token-xyz", "Push Hook"), body);
    } finally {
      root.removeHandler(capture);
      root.setLevel(original);
      root.setUseParentHandlers(origUseParent);
    }

    String logs = buf.toString(StandardCharsets.UTF_8);
    assertFalse(logs.contains(TOKEN), "logs leaked the expected token: " + logs);
  }

  // ── 9. constant-time helper ──────────────────────────────────────────────────

  @Test
  void constantTimeEquals_returnsTrueForEqual_andFalseForDifferentLength() {
    assertTrue(GitlabWebhookApi.constantTimeEquals("abc", "abc"));
    assertFalse(GitlabWebhookApi.constantTimeEquals("abc", "abcd"));
    assertFalse(GitlabWebhookApi.constantTimeEquals("abc", "abd"));
  }

  // ── ref matcher smoke ────────────────────────────────────────────────────────

  @Test
  void refMatcher_emptyPatternsMatchesAny() {
    assertTrue(GitlabWebhookApi.refMatches(List.of(), "anything"));
  }

  @Test
  void refMatcher_doubleStarPrefix() {
    assertTrue(GitlabWebhookApi.refMatches(List.of("feat/**"), "feat/x"));
    assertFalse(GitlabWebhookApi.refMatches(List.of("feat/**"), "trunk"));
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  // ── #69: enqueue failure must NEVER be swallowed into a 2xx ─────────────────

  @Test
  void enqueueFailure_returns500_andLogsSevere() {
    // Job known to the JobService but with no titan.jobs row — allocation inside enqueueBuild
    // throws. Mirrors GithubWebhookApiTest; GitLab only retries deliveries on non-2xx.
    String configJson =
        "{\"triggers\":[{\"type\":\"gitlab\",\"id\":\"trig-1\",\"branches\":[\"trunk\"],"
            + "\"events\":[\"push\"],\"credentialsId\":\"gl-token\"}]}";
    jobs.add(
        new Job(
            999_999L, "group/repo-phantom", null, null, "", configJson, null, null, null, true));
    creds.put("gitlab-webhook", "gl-token", TOKEN);

    List<LogRecord> records = new java.util.ArrayList<>();
    Logger logger = Logger.getLogger(GitlabWebhookApi.class.getName());
    Handler capture = recordingHandler(records);
    logger.addHandler(capture);
    Response resp;
    try {
      byte[] body = pushBody("refs/heads/trunk", "abc");
      resp = api.receive(headers(TOKEN, "Push Hook"), body);
    } finally {
      logger.removeHandler(capture);
    }

    assertEquals(
        500, resp.getStatus(), "enqueue failure must surface as non-2xx so GitLab retries (#69)");
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

  private long seedJob(@NonNull String fullName, @NonNull String ref, @NonNull String eventName) {
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson =
        "{\"triggers\":[{\"type\":\"gitlab\","
            + "\"id\":\"trig-1\","
            + "\"branches\":[\""
            + ref
            + "\"],"
            + "\"events\":[\""
            + eventName
            + "\"],"
            + "\"credentialsId\":\"gl-token\"}]}";
    long jobId = stores.jobs().insert(row);
    jobs.add(new Job(jobId, fullName, null, null, "", row.configJson, null, null, null, true));
    return jobId;
  }

  private static byte[] pushBody(@NonNull String ref, @NonNull String sha) {
    return ("{\"object_kind\":\"push\",\"ref\":\""
            + ref
            + "\",\"checkout_sha\":\""
            + sha
            + "\",\"user_username\":\"alice\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] tagPushBody(@NonNull String ref, @NonNull String sha) {
    return ("{\"object_kind\":\"tag_push\",\"ref\":\""
            + ref
            + "\",\"checkout_sha\":\""
            + sha
            + "\",\"user_username\":\"alice\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] mergeRequestBody(
      @NonNull String action, @NonNull String sourceBranch, @NonNull String headSha) {
    return ("{\"object_kind\":\"merge_request\","
            + "\"user\":{\"username\":\"bob\"},"
            + "\"object_attributes\":{\"action\":\""
            + action
            + "\",\"source_branch\":\""
            + sourceBranch
            + "\",\"last_commit\":{\"id\":\""
            + headSha
            + "\"}}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @NonNull
  private static HttpHeaders headers(String token, String event) {
    Map<String, String> map = new HashMap<>();
    if (token != null) {
      map.put("X-Gitlab-Token", token);
    }
    if (event != null) {
      map.put("X-Gitlab-Event", event);
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
      headers.forEach((k, v) -> out.add(k, v));
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
    private final ByteArrayOutputStream buf;
    private final PrintStream ps;

    CapturingHandler(@NonNull ByteArrayOutputStream buf) {
      this.buf = buf;
      this.ps = new PrintStream(buf, true, StandardCharsets.UTF_8);
      setLevel(Level.ALL);
    }

    @Override
    public void publish(LogRecord record) {
      String msg = record.getMessage();
      Object[] params = record.getParameters();
      ps.println(msg);
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

    // Reference one of the imported types to avoid unused-import lint chatter.
    static {
      @SuppressWarnings("unused")
      Class<?> c1 = ConsoleHandler.class;
      @SuppressWarnings("unused")
      Class<?> c2 = LogManager.class;
    }
  }
}
