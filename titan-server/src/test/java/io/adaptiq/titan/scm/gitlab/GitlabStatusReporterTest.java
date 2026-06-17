package io.adaptiq.titan.scm.gitlab;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
 * Unit tests for {@link GitlabStatusReporter} (issue #1080).
 *
 * <p>Strategy: spin up an in-process {@link HttpServer} as the GitLab mock so the reporter
 * exercises its real {@link HttpClient}, URL construction, and error-handling branches end-to-end.
 * No Wiremock dependency, no Testcontainers — keeps the test self-contained and < 200 ms.
 *
 * <p>Matrix mirrors {@code GithubStatusReporterTest}:
 *
 * <ul>
 *   <li>state mapping (every enum value)
 *   <li>happy 201 → no throw, single POST, correct URL + query string
 *   <li>503 → no throw, build state untouched (assertion: dispatcher returned)
 *   <li>401 → no throw, log message MUST NOT contain the token
 *   <li>missing commitSha → silent skip, zero HTTP calls
 *   <li>triggerType="github-app" → silent skip, zero HTTP calls
 * </ul>
 */
class GitlabStatusReporterTest {

  private static final String TOKEN = "glpat-DO-NOT-LEAK-xyz";
  private static final String CRED_ID = "gl-pat";
  private static final long PROJECT_ID = 98765L;
  private static final String SHA = "deadbeefcafedeadbeefcafedeadbeefcafe1234";

  private HttpServer server;
  private List<RecordedRequest> recorded;
  private AtomicInteger statusToReturn;
  private GitlabStatusReporter reporter;
  private InMemoryCredentials creds;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    recorded = new ArrayList<>();
    statusToReturn = new AtomicInteger(201);
    server.createContext(
        "/",
        (HttpExchange ex) -> {
          recorded.add(
              new RecordedRequest(
                  ex.getRequestMethod(),
                  ex.getRequestURI().toString(),
                  new HashMap<>(
                      ex.getRequestHeaders().entrySet().stream()
                          .collect(
                              java.util.stream.Collectors.toMap(
                                  Map.Entry::getKey, e -> String.join(",", e.getValue()))))));
          byte[] resp = "{}".getBytes(StandardCharsets.UTF_8);
          ex.sendResponseHeaders(statusToReturn.get(), resp.length);
          ex.getResponseBody().write(resp);
          ex.close();
        });
    server.start();

    creds = new InMemoryCredentials();
    creds.put(GitlabStatusReporter.CREDENTIALS_SCOPE, CRED_ID, TOKEN);

    reporter =
        new GitlabStatusReporter(
            creds,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "https://titan.example.com",
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  // ── state mapping ────────────────────────────────────────────────────────────

  @Test
  void mapStatus_coversEveryCanonicalStatus() {
    assertEquals(Optional.of("running"), GitlabStatusReporter.mapStatus("QUEUED"));
    assertEquals(Optional.of("running"), GitlabStatusReporter.mapStatus("RUNNING"));
    assertEquals(Optional.of("success"), GitlabStatusReporter.mapStatus("SUCCESS"));
    assertEquals(Optional.of("failed"), GitlabStatusReporter.mapStatus("FAILED"));
    assertEquals(Optional.of("failed"), GitlabStatusReporter.mapStatus("UNSTABLE"));
    assertEquals(Optional.of("canceled"), GitlabStatusReporter.mapStatus("ABORTED"));
    assertEquals(Optional.of("canceled"), GitlabStatusReporter.mapStatus("CANCELLED"));
    assertEquals(Optional.empty(), GitlabStatusReporter.mapStatus("PENDING"));
    assertEquals(Optional.empty(), GitlabStatusReporter.mapStatus("SLEEPING"));
  }

  // ── happy path ───────────────────────────────────────────────────────────────

  @Test
  void successfulBuild_postsCorrectUrlAndQueryString() {
    statusToReturn.set(201);
    reporter.report(buildEvent("SUCCESS"));

    assertEquals(1, recorded.size(), "exactly one POST expected");
    RecordedRequest r = recorded.get(0);
    assertEquals("POST", r.method);
    assertTrue(
        r.uri.startsWith("/api/v4/projects/98765/statuses/" + SHA + "?"), "wrong path: " + r.uri);
    assertTrue(r.uri.contains("state=success"), "missing state: " + r.uri);
    assertTrue(r.uri.contains("context=ci%2Ftitan"), "missing context: " + r.uri);
    assertTrue(r.uri.contains("target_url=https"), "missing target_url: " + r.uri);
    // PRIVATE-TOKEN header carried the unsealed token (sent over loopback only).
    assertEquals(TOKEN, r.headers.get("Private-token"));
  }

  // ── 503 → swallow ────────────────────────────────────────────────────────────

  @Test
  void serverError_swallowed_noThrow() {
    statusToReturn.set(503);
    assertDoesNotThrow(() -> reporter.report(buildEvent("SUCCESS")));
    assertEquals(1, recorded.size(), "503 still issues exactly one POST");
  }

  // ── 401 → log MUST NOT contain the token ─────────────────────────────────────

  @Test
  void unauthorized_logsNeverContainToken() {
    statusToReturn.set(401);
    String logs = captureLogs(() -> reporter.report(buildEvent("FAILED")));
    assertFalse(logs.contains(TOKEN), "401 log leaked the token: " + logs);
    assertTrue(logs.contains("401"), "log should mention the status code: " + logs);
  }

  // ── missing commitSha → silent skip ──────────────────────────────────────────

  @Test
  void missingCommitSha_noHttpCall() {
    String meta =
        "{\"branch\":\"trunk\",\"projectId\":98765,\"gitlabCredentialsId\":\"" + CRED_ID + "\"}";
    reporter.report(new BuildStateChangedEvent(42L, "RUNNING", "gitlab", meta, 7L, 1));
    assertEquals(0, recorded.size(), "no HTTP call expected when commitSha is absent");
  }

  // ── missing projectId → silent skip ──────────────────────────────────────────

  @Test
  void missingProjectId_noHttpCall() {
    String meta =
        "{\"branch\":\"trunk\",\"commitSha\":\""
            + SHA
            + "\",\"gitlabCredentialsId\":\""
            + CRED_ID
            + "\"}";
    reporter.report(new BuildStateChangedEvent(42L, "RUNNING", "gitlab", meta, 7L, 1));
    assertEquals(0, recorded.size(), "no HTTP call expected when projectId is absent");
  }

  // ── triggerType="github-app" → silent skip ───────────────────────────────────

  @Test
  void githubTrigger_doesNotPostToGitlab() {
    reporter.report(
        new BuildStateChangedEvent(42L, "SUCCESS", "github-app:push", metaJson(), 7L, 1));
    assertEquals(0, recorded.size(), "GitHub-triggered build must not reach the GitLab reporter");
  }

  // ── credentialsId mis-configured → silent skip, no NPE ───────────────────────

  @Test
  void credentialIdMissingFromStore_noHttpCall() {
    String meta =
        "{\"commitSha\":\""
            + SHA
            + "\",\"projectId\":98765,\"gitlabCredentialsId\":\"unknown-cred\"}";
    reporter.report(new BuildStateChangedEvent(42L, "SUCCESS", "gitlab", meta, 7L, 1));
    assertEquals(0, recorded.size());
  }

  // ── intermediate state (PENDING) → silent skip ───────────────────────────────

  @Test
  void intermediateState_noHttpCall() {
    reporter.report(new BuildStateChangedEvent(42L, "PENDING", "gitlab", metaJson(), 7L, 1));
    assertEquals(0, recorded.size());
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  @NonNull
  private static BuildStateChangedEvent buildEvent(@NonNull String status) {
    return new BuildStateChangedEvent(42L, status, "gitlab", metaJson(), 7L, 1);
  }

  @NonNull
  private static String metaJson() {
    return "{\"branch\":\"trunk\",\"commitSha\":\""
        + SHA
        + "\",\"projectId\":"
        + PROJECT_ID
        + ",\"gitlabCredentialsId\":\""
        + CRED_ID
        + "\"}";
  }

  @NonNull
  private static String captureLogs(@NonNull Runnable r) {
    Logger root = Logger.getLogger(GitlabStatusReporter.class.getName());
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

  // ── recording stub ───────────────────────────────────────────────────────────

  static final class RecordedRequest {
    final String method;
    final String uri;
    final Map<String, String> headers;

    RecordedRequest(
        @NonNull String method, @NonNull String uri, @NonNull Map<String, String> hdrs) {
      this.method = method;
      this.uri = uri;
      this.headers = hdrs;
    }
  }

  // ── j.u.l capture ────────────────────────────────────────────────────────────

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

  // ── in-memory CredentialsService stub ────────────────────────────────────────

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
