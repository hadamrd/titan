package io.adaptiq.titan.scm.bitbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.adaptiq.titan.scm.Finding;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Full outbound-flow integration test for the Bitbucket Cloud adapter (issue #1117).
 *
 * <p>Drives the complete PR experience against a stateful in-process Bitbucket Cloud REST stand-in
 * (JDK {@code HttpServer} — same dependency-free approach as {@code TitanNotifyIT}; no Docker, no
 * WireMock jar needed since the adapter speaks raw {@code java.net.http}):
 *
 * <ol>
 *   <li>build start → {@code INPROGRESS} commit status posted;
 *   <li>a {@code sast} step emits a {@link Finding} → inline PR review comment posted;
 *   <li>a finding on a file NOT in the diff → falls back to a summary comment (no crash);
 *   <li>build end → {@code SUCCESSFUL} commit status posted AND the sticky PR summary comment is
 *       edited-in-place on rerun (PUT, not a second POST).
 * </ol>
 *
 * <p>Asserts request counts, the {@code Authorization} header (Basic for the PR endpoints), and
 * idempotency of the summary comment across reruns.
 */
class BitbucketReportingFlowIT {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String SCOPE = "bitbucket-webhook";
  private static final String CRED_ID = "bb-cred";
  private static final String WS = "acme";
  private static final String REPO = "widget";
  private static final String SHA = "deadbeefcafedeadbeefcafedeadbeefcafe1234";
  private static final long JOB_ID = 7L;
  private static final int PR_ID = 42;

  private BitbucketServer bb;
  private InMemoryCredentials creds;
  private BitbucketStatusReporter statusReporter;
  private BitbucketPrCommentReporter prCommentReporter;
  private BitbucketInlineReviewReporter inlineReporter;

  @BeforeEach
  void setUp() throws Exception {
    bb = new BitbucketServer();
    creds = new InMemoryCredentials();
    creds.put(SCOPE, CRED_ID, "alice:app-password-secret");

    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    statusReporter =
        new BitbucketStatusReporter(creds, bb.baseUrl(), "https://titan.example.com", http);
    BitbucketClientFactory factory = new BitbucketClientFactory(bb.baseUrl(), http, 0L);
    prCommentReporter =
        new BitbucketPrCommentReporter(creds, factory, "https://titan.example.com", true);
    // Inline review is experimental + flag-gated (no engine findings-event producer yet); the IT
    // exercises the active path explicitly with enabled=true.
    inlineReporter = new BitbucketInlineReviewReporter(creds, factory, true);
  }

  @AfterEach
  void tearDown() {
    if (bb != null) {
      bb.stop();
    }
  }

  @Test
  void fullFlow_startInprogress_inlineFinding_endSuccessful_summaryEditedOnRerun()
      throws Exception {
    BitbucketPrContext ctx = new BitbucketPrContext(WS, REPO, PR_ID, CRED_ID);

    // 1. build start → INPROGRESS
    statusReporter.report(
        new BuildStateChangedEvent(1L, "RUNNING", "bitbucket:pullrequest", metaJson(), JOB_ID, 1));
    assertEquals(1, bb.statusPosts.size(), "one commit-status POST at start");
    assertEquals("INPROGRESS", bb.statusPosts.get(0));

    // 2. sast emits findings: one anchored, one on a renamed/absent file → fallback summary
    List<Finding> findings =
        List.of(
            new Finding("src/foo.py", 3, Finding.Severity.MAJOR, "hardcoded secret"),
            new Finding("src/foo.py", 7, Finding.Severity.MINOR, "unused import"),
            new Finding("renamed.py", 1, Finding.Severity.CRITICAL, "eval() on user input"));
    BitbucketInlineReviewReporter.Result reviewResult = inlineReporter.postFindings(ctx, findings);
    assertEquals(2, reviewResult.inlinePosted(), "two findings anchored inline");
    assertEquals(1, reviewResult.fellBackToSummary(), "renamed-file finding fell back");
    assertTrue(reviewResult.summaryPosted(), "fallback summary posted");
    assertEquals(2, bb.inlineComments.size(), "two inline comments");
    assertTrue(
        bb.comments.values().stream().anyMatch(c -> c.contains("eval() on user input")),
        "fallback summary carries the un-anchored finding");

    // 3. terminal SUCCESS → first PR summary comment (POST), and SUCCESSFUL commit status
    prCommentReporter.report(
        new BuildStateChangedEvent(1L, "SUCCESS", "bitbucket:pullrequest", metaJson(), JOB_ID, 1));
    statusReporter.report(
        new BuildStateChangedEvent(1L, "SUCCESS", "bitbucket:pullrequest", metaJson(), JOB_ID, 1));
    assertEquals(2, bb.statusPosts.size(), "second commit-status POST at end");
    assertEquals("SUCCESSFUL", bb.statusPosts.get(1));

    int commentsAfterFirstSummary = bb.comments.size();
    long summaryId =
        bb.comments.entrySet().stream()
            .filter(e -> e.getValue().contains(BitbucketPrCommentReporter.markerFor(JOB_ID)))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no sticky summary comment was created"));

    // 4. rerun terminal SUCCESS → the SAME summary comment is edited in place (PUT), not appended.
    bb.puts.clear();
    prCommentReporter.report(
        new BuildStateChangedEvent(2L, "SUCCESS", "bitbucket:pullrequest", metaJson(), JOB_ID, 2));
    assertEquals(
        commentsAfterFirstSummary,
        bb.comments.size(),
        "rerun must NOT create a new comment (edit-in-place)");
    assertTrue(bb.puts.contains(summaryId), "rerun must PUT the existing summary comment id");

    // headers — PR endpoints use Basic (app-password); status endpoint uses the wired token.
    assertNotNull(bb.lastCommentAuthHeader);
    assertTrue(
        bb.lastCommentAuthHeader.startsWith("Basic "),
        "PR comments must use Basic app-password auth");
  }

  // ── stateful Bitbucket Cloud stand-in ─────────────────────────────────────────

  private static final class BitbucketServer {
    private final HttpServer server;
    final List<String> statusPosts = new ArrayList<>(); // captured "state" values
    final Map<Long, String> comments = new HashMap<>(); // id → raw body
    final List<Long> inlineComments = new ArrayList<>();
    final List<Long> puts = new ArrayList<>();
    // Files present in the PR diff — an inline comment on any other path gets a 400.
    final java.util.Set<String> diffPaths = new java.util.HashSet<>(List.of("src/foo.py"));
    private final AtomicLong nextId = new AtomicLong(1000);
    volatile String lastCommentAuthHeader;

    BitbucketServer() throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/", this::route);
      server.start();
    }

    private synchronized void route(@NonNull HttpExchange ex) throws IOException {
      String path = ex.getRequestURI().getPath();
      String method = ex.getRequestMethod();
      byte[] in = ex.getRequestBody().readAllBytes();
      String body = new String(in, StandardCharsets.UTF_8);
      try {
        if (path.endsWith("/statuses/build") && method.equals("POST")) {
          statusPosts.add(JSON.readTree(body).path("state").asText());
          reply(ex, 201, "{}");
          return;
        }
        if (path.endsWith("/comments")) {
          lastCommentAuthHeader = ex.getRequestHeaders().getFirst("Authorization");
          if (method.equals("GET")) {
            replyCommentList(ex);
            return;
          }
          if (method.equals("POST")) {
            JsonNode node = JSON.readTree(body);
            // Bitbucket rejects an inline comment whose path is not in the PR diff with 400.
            if (node.has("inline")
                && !diffPaths.contains(node.path("inline").path("path").asText())) {
              reply(ex, 400, "{\"error\":{\"message\":\"path is not part of the diff\"}}");
              return;
            }
            long id = nextId.incrementAndGet();
            comments.put(id, node.path("content").path("raw").asText());
            if (node.has("inline")) {
              inlineComments.add(id);
            }
            reply(ex, 201, "{\"id\":" + id + "}");
            return;
          }
        }
        if (path.contains("/comments/") && method.equals("PUT")) {
          long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
          puts.add(id);
          comments.put(id, JSON.readTree(body).path("content").path("raw").asText());
          reply(ex, 200, "{\"id\":" + id + "}");
          return;
        }
        reply(ex, 404, "{}");
      } catch (RuntimeException e) {
        reply(ex, 500, "{}");
      }
    }

    private void replyCommentList(@NonNull HttpExchange ex) throws IOException {
      StringBuilder sb = new StringBuilder("{\"values\":[");
      boolean first = true;
      for (Map.Entry<Long, String> e : comments.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        sb.append("{\"id\":")
            .append(e.getKey())
            .append(",\"content\":{\"raw\":")
            .append(JSON.writeValueAsString(e.getValue()))
            .append("}}");
      }
      sb.append("]}");
      reply(ex, 200, sb.toString());
    }

    private void reply(@NonNull HttpExchange ex, int status, @NonNull String body)
        throws IOException {
      byte[] out = body.getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(status, out.length);
      ex.getResponseBody().write(out);
      ex.close();
    }

    @NonNull
    String baseUrl() {
      return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void stop() {
      server.stop(0);
    }
  }

  @NonNull
  private static String metaJson() {
    return "{\"commitSha\":\""
        + SHA
        + "\",\"workspace\":\""
        + WS
        + "\",\"repoSlug\":\""
        + REPO
        + "\",\"prId\":"
        + PR_ID
        + ",\"bitbucketCredentialsId\":\""
        + CRED_ID
        + "\"}";
  }

  // ── in-memory credentials fake ────────────────────────────────────────────────

  private static final class InMemoryCredentials implements CredentialsService {
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
      return "in-memory-it";
    }

    @Override
    public int rotateKek() {
      return 0;
    }
  }
}
