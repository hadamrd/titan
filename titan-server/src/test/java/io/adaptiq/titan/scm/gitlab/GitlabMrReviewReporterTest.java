package io.adaptiq.titan.scm.gitlab;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit + integration tests for {@link GitlabMrReviewReporter} (issue #1168).
 *
 * <ul>
 *   <li>{@code parseFileLine} extracts {@code path:line}; rejects no-location, bogus, and
 *       timestamp-only sentences (adversarial).
 *   <li>Non-MR / non-failure / no-file:line builds → no HTTP.
 *   <li>A FAILED build whose failureReason maps to {@code file:line} → GET MR (for diff_refs) then
 *       POST {@code …/discussions} with a {@code position} carrying the right new_path + new_line.
 *   <li>HTTP 500 on the discussion POST → no throw, build untouched, token NEVER in logs.
 *   <li>MR with no diff_refs → no POST (can't build a valid position).
 * </ul>
 */
class GitlabMrReviewReporterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TOKEN = "glpat-DO-NOT-LEAK-review";
  private static final String CRED_ID = "gl-pat";
  private static final long PROJECT_ID = 98765L;
  private static final long MR_IID = 42L;
  private static final String SHA = "deadbeefcafedeadbeefcafedeadbeefcafe1234";

  private HttpServer server;
  private GitlabMock mock;
  private TitanStores stores;
  private GitlabMrReviewReporter reporter;
  private InMemoryCredentials creds;
  private long jobId;
  private long buildId;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    mock = new GitlabMock();
    server.createContext("/", mock);
    server.start();

    creds = new InMemoryCredentials();
    creds.put(GitlabMrReviewReporter.CREDENTIALS_SCOPE, CRED_ID, TOKEN);

    stores = FakeTitanStores.create();

    reporter =
        new GitlabMrReviewReporter(
            stores,
            creds,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            /* featureEnabled */ true,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  // ── unit: parseFileLine (happy + adversarial) ──────────────────────────────────

  @Test
  void parseFileLine_extractsPathAndLine() {
    GitlabMrReviewReporter.Annotation a =
        GitlabMrReviewReporter.parseFileLine(
            "AssertionError at src/app/login.py:88 — expected 200");
    assertNotNull(a);
    assertEquals("src/app/login.py", a.path);
    assertEquals(88, a.line);
  }

  @Test
  void parseFileLine_noLocation_returnsNull() {
    assertNull(GitlabMrReviewReporter.parseFileLine("the build failed for reasons unknown"));
  }

  @Test
  void parseFileLine_bareTimestampNotMistakenForLocation() {
    // "12:34" has no file extension before the colon → must NOT be treated as a location.
    assertNull(GitlabMrReviewReporter.parseFileLine("timed out after 12:34 minutes"));
  }

  @Test
  void parseFileLine_zeroLine_returnsNull() {
    assertNull(GitlabMrReviewReporter.parseFileLine("weird marker file.py:0 here"));
  }

  // ── unit: no-op filters ─────────────────────────────────────────────────────────

  @Test
  void nonGitlabTrigger_noHttp() {
    seedBuild("FAILED", "boom src/app/login.py:88");
    reporter.report(
        new BuildStateChangedEvent(buildId, "FAILED", "github-app", mrMeta(), jobId, 1));
    assertEquals(
        0, mock.total(), "GitHub-triggered build must not reach the GitLab review reporter");
  }

  @Test
  void successBuild_noHttp() {
    seedBuild("SUCCESS", null);
    reporter.report(new BuildStateChangedEvent(buildId, "SUCCESS", "gitlab", mrMeta(), jobId, 1));
    assertEquals(0, mock.total(), "a SUCCESS build gets no inline review comments");
  }

  @Test
  void failureWithoutFileLine_noHttp() {
    seedBuild("FAILED", "infra error: could not reach the agent");
    reporter.report(new BuildStateChangedEvent(buildId, "FAILED", "gitlab", mrMeta(), jobId, 1));
    assertEquals(0, mock.total(), "a failure with no file:line yields no inline comment");
  }

  @Test
  void nonMrBuild_noHttp() {
    seedBuild("FAILED", "boom src/app/login.py:88");
    String meta =
        "{\"commitSha\":\""
            + SHA
            + "\",\"projectId\":"
            + PROJECT_ID
            + ",\"gitlabCredentialsId\":\""
            + CRED_ID
            + "\"}"; // no mrIid
    reporter.report(new BuildStateChangedEvent(buildId, "FAILED", "gitlab", meta, jobId, 1));
    assertEquals(0, mock.total(), "a push build (no mrIid) must be a no-op");
  }

  // ── integration: line-level discussion carries the right position ──────────────

  @Test
  void failureMappedToFileLine_postsDiscussionWithPosition() throws Exception {
    seedBuild("FAILED", "AssertionError at src/app/login.py:88 — expected 200, got 500");
    reporter.report(new BuildStateChangedEvent(buildId, "FAILED", "gitlab", mrMeta(), jobId, 1));

    assertEquals(1, mock.posts.size(), "exactly one discussion POST expected");
    RecordedRequest post = mock.posts.get(0);
    assertTrue(
        post.uri.endsWith("/api/v4/projects/98765/merge_requests/42/discussions"),
        "wrong discussions path: " + post.uri);
    assertEquals(TOKEN, post.headers.get("Private-token"));

    JsonNode body = MAPPER.readTree(post.body);
    JsonNode pos = body.path("position");
    assertEquals("text", pos.path("position_type").asText());
    assertEquals("src/app/login.py", pos.path("new_path").asText());
    assertEquals(88, pos.path("new_line").asInt());
    // diff_refs flowed through from the GET MR response.
    assertEquals("base000", pos.path("base_sha").asText());
    assertEquals("start000", pos.path("start_sha").asText());
    assertEquals("head000", pos.path("head_sha").asText());
  }

  // ── adversarial: 500 swallowed, build untouched, token never logged ─────────────

  @Test
  void discussionPost500_swallowed_tokenNeverLogged() {
    seedBuild("FAILED", "AssertionError at src/app/login.py:88");
    mock.failPostStatus.set(500);
    String logs =
        captureLogs(
            () ->
                assertDoesNotThrow(
                    () ->
                        reporter.report(
                            new BuildStateChangedEvent(
                                buildId, "FAILED", "gitlab", mrMeta(), jobId, 1))));
    assertEquals(1, mock.posts.size(), "a 500 still issued exactly one POST attempt");
    assertFalse(logs.contains(TOKEN), "500 log leaked the token: " + logs);
    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertEquals("FAILED", row.status, "the reporter must not have mutated build state");
  }

  // ── adversarial: MR with no diff_refs → no POST ─────────────────────────────────

  @Test
  void noDiffRefs_noDiscussionPosted() {
    seedBuild("FAILED", "AssertionError at src/app/login.py:88");
    mock.includeDiffRefs.set(false);
    reporter.report(new BuildStateChangedEvent(buildId, "FAILED", "gitlab", mrMeta(), jobId, 1));
    assertEquals(0, mock.posts.size(), "without diff_refs an inline position can't be built");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  @NonNull
  private static String mrMeta() {
    return "{\"branch\":\"feature\",\"commitSha\":\""
        + SHA
        + "\",\"projectId\":"
        + PROJECT_ID
        + ",\"mrIid\":"
        + MR_IID
        + ",\"gitlabCredentialsId\":\""
        + CRED_ID
        + "\"}";
  }

  private void seedBuild(@NonNull String status, String failureReason) {
    stores.withTransaction(
        conn -> {
          try (java.sql.PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.jobs (full_name, display_name, pipeline_script, config_json, "
                      + "enabled) VALUES (?, ?, ?, '{}', TRUE)")) {
            ps.setString(1, "acme/widget");
            ps.setString(2, "Widget CI");
            ps.setString(3, "pipeline {}");
            ps.executeUpdate();
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
          return null;
        });
    jobId = stores.jobs().findByFullName("acme/widget").orElseThrow().id;

    Instant start = Instant.parse("2026-05-26T09:55:00Z");
    buildId =
        stores.withTransaction(
            conn -> {
              int n = stores.builds().nextBuildNumber(conn, jobId);
              BuildRow row = new BuildRow();
              row.jobId = jobId;
              row.buildNumber = n;
              row.status = status;
              row.queuedAt = start;
              row.startedAt = start;
              row.triggerType = "gitlab";
              row.triggerMetaJson = mrMeta();
              return stores.builds().insert(conn, row);
            });

    if (failureReason != null) {
      FlowNodeRow node = new FlowNodeRow();
      node.buildId = buildId;
      node.nodeId = "step-test";
      node.nodeType = "STEP";
      node.displayName = "pytest";
      node.status = "FAILED";
      node.failureCategory = "STEP_EXIT";
      node.failureReason = failureReason;
      node.startedAt = start;
      node.completedAt = start.plusSeconds(5);
      node.durationMs = 5_000L;
      stores.flowNodes().insert(node);
    }
  }

  @NonNull
  private static String captureLogs(@NonNull Runnable r) {
    Logger root = Logger.getLogger(GitlabMrReviewReporter.class.getName());
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    Handler h = new CapturingHandler(buf);
    Level original = root.getLevel();
    boolean origUseParent = root.getUseParentHandlers();
    root.addHandler(h);
    root.setLevel(Level.ALL);
    root.setUseParentHandlers(false);
    try {
      r.run();
    } finally {
      root.removeHandler(h);
      root.setLevel(original);
      root.setUseParentHandlers(origUseParent);
    }
    return buf.toString(StandardCharsets.UTF_8);
  }

  // ── stateful GitLab mock ───────────────────────────────────────────────────────

  static final class GitlabMock implements com.sun.net.httpserver.HttpHandler {
    final List<RecordedRequest> posts = new ArrayList<>();
    final AtomicInteger failPostStatus = new AtomicInteger(0);
    final java.util.concurrent.atomic.AtomicBoolean includeDiffRefs =
        new java.util.concurrent.atomic.AtomicBoolean(true);

    int total() {
      return posts.size();
    }

    @Override
    public void handle(HttpExchange ex) throws java.io.IOException {
      String method = ex.getRequestMethod();
      String uri = ex.getRequestURI().toString();
      byte[] reqBody = ex.getRequestBody().readAllBytes();
      Map<String, String> headers =
          ex.getRequestHeaders().entrySet().stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      Map.Entry::getKey, e -> String.join(",", e.getValue())));
      try {
        if ("GET".equals(method)) {
          // GET on the MR → diff_refs (or not, to drive the no-diff_refs branch).
          String json =
              includeDiffRefs.get()
                  ? "{\"iid\":42,\"diff_refs\":{\"base_sha\":\"base000\",\"start_sha\":\"start000\",\"head_sha\":\"head000\"}}"
                  : "{\"iid\":42}";
          respond(ex, 200, json.getBytes(StandardCharsets.UTF_8));
          return;
        }
        if ("POST".equals(method)) {
          posts.add(new RecordedRequest(method, uri, headers, reqBody));
          int forced = failPostStatus.get();
          respond(
              ex,
              forced != 0 ? forced : 201,
              "{\"id\":\"disc-1\"}".getBytes(StandardCharsets.UTF_8));
          return;
        }
        respond(ex, 405, new byte[0]);
      } finally {
        ex.close();
      }
    }

    private static void respond(HttpExchange ex, int status, byte[] body)
        throws java.io.IOException {
      ex.sendResponseHeaders(status, body.length);
      ex.getResponseBody().write(body);
    }
  }

  static final class RecordedRequest {
    final String method;
    final String uri;
    final Map<String, String> headers;
    final byte[] body;

    RecordedRequest(
        @NonNull String method,
        @NonNull String uri,
        @NonNull Map<String, String> headers,
        @NonNull byte[] body) {
      this.method = method;
      this.uri = uri;
      this.headers = headers;
      this.body = body;
    }
  }

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
    public void close() {
      assertNotNull(buf);
    }
  }

  static final class InMemoryCredentials implements CredentialsService {
    private final Map<String, String> store = new java.util.HashMap<>();

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
}
