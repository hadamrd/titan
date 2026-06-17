package io.adaptiq.titan.scm.gitlab;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.HashMap;
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
 * Unit + integration tests for {@link GitlabMrCommentReporter} (issue #1168). Mirrors the
 * scaffolding of {@link GitlabStatusReporterTest}: an in-process {@link HttpServer} stands in for
 * the GitLab API so the reporter exercises its real {@link HttpClient}, URL construction, and
 * list-and-match dedupe end-to-end; an in-memory H2 via {@link FakeTitanStores} provides the
 * job/build/flow-node rows the body renderer reads.
 *
 * <p>Matrix (from the ticket):
 *
 * <ul>
 *   <li>Body renderer: pending / running / success / failed each render the marker + status + build
 *       deep-link.
 *   <li>Non-MR trigger (github / push without mrIid) → no HTTP.
 *   <li>Intermediate state (PENDING / SLEEPING) → no HTTP.
 *   <li>First terminal event → POST {@code …/notes} exactly once; body carries marker + deep-link.
 *   <li>Second event on the same build → list-notes finds the marker note → PUT exactly once, ZERO
 *       additional POSTs.
 *   <li>HTTP 500 on POST → no throw, build-state untouched, token NEVER in logs.
 *   <li>Missing credential → no HTTP.
 * </ul>
 */
class GitlabMrCommentReporterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String TOKEN = "glpat-DO-NOT-LEAK-comment";
  private static final String CRED_ID = "gl-pat";
  private static final long PROJECT_ID = 98765L;
  private static final long MR_IID = 42L;
  private static final String SHA = "deadbeefcafedeadbeefcafedeadbeefcafe1234";

  private HttpServer server;
  private GitlabMock mock;
  private TitanStores stores;
  private GitlabMrCommentReporter reporter;
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
    creds.put(GitlabMrCommentReporter.CREDENTIALS_SCOPE, CRED_ID, TOKEN);

    stores = FakeTitanStores.create();
    seedJobBuildAndStages();

    reporter =
        new GitlabMrCommentReporter(
            stores,
            creds,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "https://titan.example.com",
            /* featureEnabled */ true,
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  // ── unit: status mapping ─────────────────────────────────────────────────────

  @Test
  void mapStatus_distinguishesPendingFromRunningAndSkipsIntermediate() {
    assertEquals(Optional.of("pending"), GitlabMrCommentReporter.mapStatus("QUEUED"));
    assertEquals(Optional.of("running"), GitlabMrCommentReporter.mapStatus("RUNNING"));
    assertEquals(Optional.of("success"), GitlabMrCommentReporter.mapStatus("SUCCESS"));
    assertEquals(Optional.of("failed"), GitlabMrCommentReporter.mapStatus("FAILED"));
    assertEquals(Optional.of("failed"), GitlabMrCommentReporter.mapStatus("UNSTABLE"));
    assertEquals(Optional.of("canceled"), GitlabMrCommentReporter.mapStatus("ABORTED"));
    assertEquals(Optional.of("canceled"), GitlabMrCommentReporter.mapStatus("CANCELLED"));
    assertEquals(Optional.empty(), GitlabMrCommentReporter.mapStatus("PENDING"));
    assertEquals(Optional.empty(), GitlabMrCommentReporter.mapStatus("SLEEPING"));
    assertEquals(Optional.empty(), GitlabMrCommentReporter.mapStatus("SKIPPED"));
  }

  // ── unit: body renderer renders marker + status + deep-link for each phase ─────

  @Test
  void renderBody_eachPhase_carriesMarkerStatusAndDeepLink() {
    for (String status : new String[] {"pending", "running", "success", "failed"}) {
      String body = reporter.renderBody(null, null, List.of(), status, 7L);
      assertTrue(
          body.startsWith(GitlabMrCommentReporter.COMMENT_MARKER),
          "body for '" + status + "' must start with the dedupe marker: " + body);
      assertTrue(body.contains(status), "body must contain status '" + status + "': " + body);
      assertTrue(
          body.contains("https://titan.example.com/builds/7"),
          "body for '" + status + "' must carry the build deep-link: " + body);
    }
  }

  // ── unit: no-op filters (never touch HTTP) ─────────────────────────────────────

  @Test
  void nonGitlabTrigger_noHttp() {
    reporter.report(
        new BuildStateChangedEvent(buildId, "SUCCESS", "github-app:push", mrMeta(), jobId, 1));
    assertEquals(0, mock.total(), "GitHub-triggered build must not reach the GitLab reporter");
  }

  @Test
  void pushBuildWithoutMrIid_noHttp() {
    String meta =
        "{\"branch\":\"trunk\",\"commitSha\":\""
            + SHA
            + "\",\"projectId\":"
            + PROJECT_ID
            + ",\"gitlabCredentialsId\":\""
            + CRED_ID
            + "\"}";
    reporter.report(new BuildStateChangedEvent(buildId, "SUCCESS", "gitlab", meta, jobId, 1));
    assertEquals(0, mock.total(), "a push build (no mrIid) must be a no-op");
  }

  @Test
  void intermediateState_noHttp() {
    reporter.report(new BuildStateChangedEvent(buildId, "PENDING", "gitlab", mrMeta(), jobId, 1));
    assertEquals(0, mock.total(), "intermediate PENDING must not post");
  }

  @Test
  void missingCredential_noHttp() {
    String meta =
        "{\"commitSha\":\""
            + SHA
            + "\",\"projectId\":"
            + PROJECT_ID
            + ",\"mrIid\":"
            + MR_IID
            + ",\"gitlabCredentialsId\":\"unknown-cred\"}";
    reporter.report(new BuildStateChangedEvent(buildId, "SUCCESS", "gitlab", meta, jobId, 1));
    assertEquals(0, mock.total(), "an unresolvable credential must not reach the GitLab API");
  }

  // ── integration: first event POSTs once ────────────────────────────────────────

  @Test
  void firstTerminalEvent_postsExactlyOneNoteWithMarkerAndDeepLink() {
    reporter.report(new BuildStateChangedEvent(buildId, "FAILED", "gitlab", mrMeta(), jobId, 1));

    assertEquals(1, mock.posts.size(), "exactly one POST expected on the first event");
    assertEquals(0, mock.puts.size(), "no PUT on the first event");
    RecordedRequest post = mock.posts.get(0);
    assertTrue(
        post.uri.endsWith("/api/v4/projects/98765/merge_requests/42/notes"),
        "wrong notes path: " + post.uri);
    assertEquals(TOKEN, post.headers.get("Private-token"));
    String body = noteBodyOf(post);
    assertTrue(body.startsWith(GitlabMrCommentReporter.COMMENT_MARKER), "missing marker: " + body);
    assertTrue(body.contains("failed"), "missing status: " + body);
    assertTrue(
        body.contains("https://titan.example.com/builds/" + buildId), "missing deep-link: " + body);
  }

  // ── integration: second event PUTs in place, no duplicate POST ──────────────────

  @Test
  void secondEventOnSameBuild_updatesInPlaceNeverDuplicates() {
    reporter.report(new BuildStateChangedEvent(buildId, "RUNNING", "gitlab", mrMeta(), jobId, 1));
    reporter.report(new BuildStateChangedEvent(buildId, "SUCCESS", "gitlab", mrMeta(), jobId, 1));

    assertEquals(1, mock.posts.size(), "the note must be POSTed exactly once across both events");
    assertEquals(1, mock.puts.size(), "the second event must PUT (update) exactly once");
    RecordedRequest put = mock.puts.get(0);
    assertTrue(
        put.uri.matches(".*/api/v4/projects/98765/merge_requests/42/notes/\\d+"),
        "PUT must target the existing note id: " + put.uri);
    assertTrue(noteBodyOf(put).contains("success"), "updated body should reflect SUCCESS");
  }

  // ── regression: marker note past page 1 is found via pagination (no duplicate) ──

  @Test
  void markerNoteOnPage2_foundViaPagination_updatesInPlaceNeverDuplicates() {
    // An active MR with >100 notes pushes the sticky Titan note onto page 2. Without pagination the
    // reporter would miss it and POST a duplicate on every build-state event (issue #1168 review,
    // sev2/correctness). With pagination it follows X-Next-Page, finds the note, and PUTs.
    mock.paginateMarkerOnPage2.set(true);

    reporter.report(new BuildStateChangedEvent(buildId, "SUCCESS", "gitlab", mrMeta(), jobId, 1));

    assertEquals(
        0, mock.posts.size(), "marker note lives on page 2 → must be found, never re-POSTed");
    assertEquals(1, mock.puts.size(), "the existing page-2 note must be updated in place");
    assertTrue(
        mock.getPages.contains(2),
        "pagination must have followed X-Next-Page to page 2; pages seen: " + mock.getPages);
    RecordedRequest put = mock.puts.get(0);
    assertTrue(
        put.uri.endsWith("/notes/" + GitlabMock.PAGE2_NOTE_ID),
        "PUT must target the page-2 note id: " + put.uri);
  }

  // ── regression: commitSha is optional — an MR build without it still posts ──────

  @Test
  void mrBuildWithoutCommitSha_stillPostsNote() {
    // commitSha is unused by the note reporter; gating on it would no-op an addressable MR build
    // (issue #1168 review, sev3/correctness).
    String meta =
        "{\"branch\":\"feature\",\"projectId\":"
            + PROJECT_ID
            + ",\"mrIid\":"
            + MR_IID
            + ",\"gitlabCredentialsId\":\""
            + CRED_ID
            + "\"}"; // no commitSha
    reporter.report(new BuildStateChangedEvent(buildId, "FAILED", "gitlab", meta, jobId, 1));
    assertEquals(1, mock.posts.size(), "an MR build without commitSha must still post the note");
  }

  // ── adversarial: 500 on POST is swallowed, build untouched, token never logged ──

  @Test
  void serverError500_swallowed_tokenNeverLogged() {
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
    // Build row in the fake store is unaffected — the observer is downstream of the DB write.
    BuildRow row = stores.builds().findById(buildId).orElseThrow();
    assertEquals("SUCCESS", row.status, "the reporter must not have mutated build state");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  @NonNull
  private String noteBodyOf(@NonNull RecordedRequest r) {
    try {
      return MAPPER.readTree(r.body).path("body").asText("");
    } catch (Exception e) {
      throw new AssertionError("request body was not JSON: " + r.body, e);
    }
  }

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

  private void seedJobBuildAndStages() {
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
    Instant end = Instant.parse("2026-05-26T09:56:30Z");
    buildId =
        stores.withTransaction(
            conn -> {
              int n = stores.builds().nextBuildNumber(conn, jobId);
              BuildRow row = new BuildRow();
              row.jobId = jobId;
              row.buildNumber = n;
              row.status = "SUCCESS";
              row.queuedAt = start;
              row.startedAt = start;
              row.finishedAt = end;
              row.durationMs = 90_000L;
              row.triggerType = "gitlab";
              row.triggerMetaJson = mrMeta();
              return stores.builds().insert(conn, row);
            });

    FlowNodeRow stage = new FlowNodeRow();
    stage.buildId = buildId;
    stage.nodeId = "stage-test";
    stage.nodeType = "STAGE";
    stage.displayName = "Test";
    stage.status = "FAILED";
    stage.startedAt = start;
    stage.completedAt = end;
    stage.durationMs = 90_000L;
    stores.flowNodes().insert(stage);
  }

  @NonNull
  private static String captureLogs(@NonNull Runnable r) {
    Logger root = Logger.getLogger(GitlabMrCommentReporter.class.getName());
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

  /**
   * A tiny stateful GitLab notes API: keeps an in-memory list of notes so a POST followed by a GET
   * realistically surfaces the just-created note (carrying the marker), which drives the PUT branch
   * on the second event. Records every POST / PUT for assertions.
   */
  static final class GitlabMock implements com.sun.net.httpserver.HttpHandler {
    final List<RecordedRequest> posts = new ArrayList<>();
    final List<RecordedRequest> puts = new ArrayList<>();
    final List<Map<String, Object>> notes = new ArrayList<>();
    final List<Integer> getPages = new ArrayList<>();
    final AtomicInteger failPostStatus = new AtomicInteger(0);
    final java.util.concurrent.atomic.AtomicBoolean paginateMarkerOnPage2 =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private final AtomicInteger nextId = new AtomicInteger(1000);

    /** Note id the marker note carries when {@link #paginateMarkerOnPage2} is on. */
    static final long PAGE2_NOTE_ID = 7777L;

    int total() {
      return posts.size() + puts.size();
    }

    private static int parsePage(@NonNull String uri) {
      // lastIndexOf so the real `&page=` wins over the `page=` inside `per_page=`.
      int q = uri.lastIndexOf("page=");
      if (q < 0) {
        return 1;
      }
      int start = q + "page=".length();
      int end = start;
      while (end < uri.length() && Character.isDigit(uri.charAt(end))) {
        end++;
      }
      try {
        return Integer.parseInt(uri.substring(start, end));
      } catch (NumberFormatException e) {
        return 1;
      }
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
      RecordedRequest rec = new RecordedRequest(method, uri, headers, reqBody);

      try {
        if ("GET".equals(method)) {
          if (paginateMarkerOnPage2.get()) {
            int page = parsePage(uri);
            getPages.add(page);
            if (page <= 1) {
              // A full page of 100 noise notes (no marker) + an X-Next-Page header pointing at 2.
              List<Map<String, Object>> noise = new ArrayList<>();
              for (int i = 0; i < 100; i++) {
                Map<String, Object> m = new HashMap<>();
                m.put("id", i + 1);
                m.put("body", "just MR chatter #" + i);
                noise.add(m);
              }
              ex.getResponseHeaders().add("X-Next-Page", "2");
              respond(ex, 200, MAPPER.writeValueAsBytes(noise));
            } else {
              // Page 2 carries the sticky Titan note — no X-Next-Page → last page.
              Map<String, Object> markerNote = new HashMap<>();
              markerNote.put("id", PAGE2_NOTE_ID);
              markerNote.put("body", GitlabMrCommentReporter.COMMENT_MARKER + "\nstale body");
              respond(ex, 200, MAPPER.writeValueAsBytes(List.of(markerNote)));
            }
            return;
          }
          respond(ex, 200, MAPPER.writeValueAsBytes(notes));
          return;
        }
        if ("POST".equals(method)) {
          posts.add(rec);
          int forced = failPostStatus.get();
          if (forced != 0) {
            respond(ex, forced, "{\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8));
            return;
          }
          Map<String, Object> note = new HashMap<>();
          note.put("id", nextId.getAndIncrement());
          note.put("body", MAPPER.readTree(reqBody).path("body").asText(""));
          notes.add(note);
          respond(ex, 201, MAPPER.writeValueAsBytes(note));
          return;
        }
        if ("PUT".equals(method)) {
          puts.add(rec);
          respond(ex, 200, "{}".getBytes(StandardCharsets.UTF_8));
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
}
