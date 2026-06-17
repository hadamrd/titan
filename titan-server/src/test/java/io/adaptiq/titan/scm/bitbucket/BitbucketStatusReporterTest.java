package io.adaptiq.titan.scm.bitbucket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Unit tests for {@link BitbucketStatusReporter} (issue #1080). Mirrors the GitLab reporter's test
 * shape — in-process {@link HttpServer}, no Wiremock dependency.
 *
 * <p>Also asserts the rendered JSON body matches the frozen fixture {@code
 * src/test/resources/scm/bitbucket-status-payload.json} via parsed-equality (key order
 * insensitive).
 */
class BitbucketStatusReporterTest {

  private static final String TOKEN = "bbat-DO-NOT-LEAK-xyz";
  private static final String CRED_ID = "bb-token";
  private static final String WS = "acme";
  private static final String REPO = "widget";
  private static final String SHA = "deadbeefcafedeadbeefcafedeadbeefcafe1234";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private HttpServer server;
  private List<Recorded> recorded;
  private AtomicInteger statusToReturn;
  private BitbucketStatusReporter reporter;
  private InMemoryCredentials creds;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    recorded = new ArrayList<>();
    statusToReturn = new AtomicInteger(201);
    server.createContext(
        "/",
        (HttpExchange ex) -> {
          byte[] body = ex.getRequestBody().readAllBytes();
          recorded.add(
              new Recorded(
                  ex.getRequestMethod(),
                  ex.getRequestURI().toString(),
                  body,
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
    creds.put(BitbucketStatusReporter.CREDENTIALS_SCOPE, CRED_ID, TOKEN);

    reporter =
        new BitbucketStatusReporter(
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
    assertEquals(Optional.of("INPROGRESS"), BitbucketStatusReporter.mapStatus("QUEUED"));
    assertEquals(Optional.of("INPROGRESS"), BitbucketStatusReporter.mapStatus("RUNNING"));
    assertEquals(Optional.of("SUCCESSFUL"), BitbucketStatusReporter.mapStatus("SUCCESS"));
    assertEquals(Optional.of("FAILED"), BitbucketStatusReporter.mapStatus("FAILED"));
    assertEquals(Optional.of("FAILED"), BitbucketStatusReporter.mapStatus("UNSTABLE"));
    assertEquals(Optional.of("FAILED"), BitbucketStatusReporter.mapStatus("CANCELLED"));
    assertEquals(Optional.of("FAILED"), BitbucketStatusReporter.mapStatus("ABORTED"));
    assertEquals(Optional.empty(), BitbucketStatusReporter.mapStatus("PENDING"));
  }

  // ── happy path + fixture parity ──────────────────────────────────────────────

  @Test
  void successfulBuild_postsBodyMatchingFixture() throws Exception {
    statusToReturn.set(201);
    reporter.report(buildEvent("SUCCESS"));

    assertEquals(1, recorded.size());
    Recorded r = recorded.get(0);
    assertEquals("POST", r.method);
    assertEquals(
        "/2.0/repositories/" + WS + "/" + REPO + "/commit/" + SHA + "/statuses/build",
        r.uri,
        "wrong path");
    assertEquals("Bearer " + TOKEN, r.headers.get("Authorization"));

    JsonNode actual = MAPPER.readTree(r.body);
    JsonNode expected =
        MAPPER.readTree(
            BitbucketStatusReporterTest.class
                .getClassLoader()
                .getResourceAsStream("scm/bitbucket-status-payload.json"));
    assertEquals(expected, actual, "body must match frozen fixture (parsed-equality)");
  }

  // ── 503 → swallow ────────────────────────────────────────────────────────────

  @Test
  void serverError_swallowed_noThrow() {
    statusToReturn.set(503);
    assertDoesNotThrow(() -> reporter.report(buildEvent("FAILED")));
    assertEquals(1, recorded.size());
  }

  // ── 401 → log MUST NOT contain the token ─────────────────────────────────────

  @Test
  void unauthorized_logsNeverContainToken() {
    statusToReturn.set(401);
    String logs = captureLogs(() -> reporter.report(buildEvent("SUCCESS")));
    assertFalse(logs.contains(TOKEN), "401 log leaked the token: " + logs);
    assertTrue(logs.contains("401"), "log should mention status code: " + logs);
  }

  // ── missing commitSha → silent skip ──────────────────────────────────────────

  @Test
  void missingCommitSha_noHttpCall() {
    String meta =
        "{\"workspace\":\""
            + WS
            + "\",\"repoSlug\":\""
            + REPO
            + "\",\"bitbucketCredentialsId\":\""
            + CRED_ID
            + "\"}";
    reporter.report(new BuildStateChangedEvent(42L, "RUNNING", "bitbucket", meta, 7L, 1));
    assertEquals(0, recorded.size());
  }

  // ── missing workspace → silent skip ──────────────────────────────────────────

  @Test
  void missingWorkspace_noHttpCall() {
    String meta =
        "{\"commitSha\":\""
            + SHA
            + "\",\"repoSlug\":\""
            + REPO
            + "\",\"bitbucketCredentialsId\":\""
            + CRED_ID
            + "\"}";
    reporter.report(new BuildStateChangedEvent(42L, "SUCCESS", "bitbucket", meta, 7L, 1));
    assertEquals(0, recorded.size());
  }

  // ── triggerType="gitlab" → silent skip ───────────────────────────────────────

  @Test
  void gitlabTrigger_doesNotPostToBitbucket() {
    reporter.report(new BuildStateChangedEvent(42L, "SUCCESS", "gitlab", metaJson(), 7L, 1));
    assertEquals(0, recorded.size());
  }

  // ── unresolvable credentialsId → silent skip ─────────────────────────────────

  @Test
  void credentialIdMissingFromStore_noHttpCall() {
    String meta =
        "{\"commitSha\":\""
            + SHA
            + "\",\"workspace\":\""
            + WS
            + "\",\"repoSlug\":\""
            + REPO
            + "\",\"bitbucketCredentialsId\":\"unknown\"}";
    reporter.report(new BuildStateChangedEvent(42L, "SUCCESS", "bitbucket", meta, 7L, 1));
    assertEquals(0, recorded.size());
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  @NonNull
  private static BuildStateChangedEvent buildEvent(@NonNull String status) {
    return new BuildStateChangedEvent(42L, status, "bitbucket", metaJson(), 7L, 1);
  }

  @NonNull
  private static String metaJson() {
    return "{\"commitSha\":\""
        + SHA
        + "\",\"workspace\":\""
        + WS
        + "\",\"repoSlug\":\""
        + REPO
        + "\",\"bitbucketCredentialsId\":\""
        + CRED_ID
        + "\"}";
  }

  @NonNull
  private static String captureLogs(@NonNull Runnable r) {
    Logger root = Logger.getLogger(BitbucketStatusReporter.class.getName());
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

  static final class Recorded {
    final String method;
    final String uri;
    final byte[] body;
    final Map<String, String> headers;

    Recorded(
        @NonNull String method,
        @NonNull String uri,
        @NonNull byte[] body,
        @NonNull Map<String, String> hdrs) {
      this.method = method;
      this.uri = uri;
      this.body = body;
      this.headers = hdrs;
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
