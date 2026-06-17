package io.adaptiq.titan.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.flow.model.NotifyHook;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NotificationDispatcher}'s Slack branch (#358).
 *
 * <p>Uses the JDK's {@code com.sun.net.httpserver.HttpServer} as a stand-in for the Slack
 * inbound-webhook endpoint, and a hand-rolled stub of {@link CredentialsService} so we can assert
 * (a) it is called exactly once per dispatch and (b) the resolved URL is the one the dispatcher
 * actually POSTs to. The body is asserted against the Slack block-kit shape.
 */
class NotificationDispatcherTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private HttpServer server;
  private int port;
  private ConcurrentLinkedQueue<String> deliveries;

  @BeforeEach
  void setUp() throws Exception {
    deliveries = new ConcurrentLinkedQueue<>();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/slack",
        ex -> {
          byte[] body = ex.getRequestBody().readAllBytes();
          deliveries.add(new String(body, StandardCharsets.UTF_8));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    server.start();
    port = server.getAddress().getPort();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void slackHook_resolvesCredentialOnceAndPostsBlockKit() throws Exception {
    String slackUrl = "http://127.0.0.1:" + port + "/slack";
    CountingCredentialsService creds =
        new CountingCredentialsService("default", "slack-prod", slackUrl);

    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(http, creds);

    NotifyHook hook = new NotifyHook();
    hook.setType("slack");
    hook.setOn(List.of("failure"));
    hook.setCredentialsId("slack-prod");
    hook.setChannel("#deploys");

    dispatcher.fireStageHooks(42L, "Build", "FAILED", List.of(hook));

    // exactly one resolve, exactly one POST
    assertEquals(1, creds.calls.get(), "CredentialsService.resolvePlaintext called exactly once");
    waitForDelivery(Duration.ofSeconds(3));
    assertEquals(1, deliveries.size(), "exactly one POST to the resolved Slack URL");

    JsonNode body = JSON.readTree(deliveries.peek());
    // block-kit shape
    assertNotNull(body.get("text"), "block-kit payload has a top-level 'text' fallback");
    JsonNode blocks = body.get("blocks");
    assertNotNull(blocks);
    assertTrue(blocks.isArray() && blocks.size() >= 2, "two blocks: section + context");
    JsonNode section = blocks.get(0);
    assertEquals("section", section.get("type").asText());
    JsonNode sectionText = section.get("text");
    assertEquals("mrkdwn", sectionText.get("type").asText());
    assertTrue(
        sectionText.get("text").asText().contains("FAILED"),
        "section body mentions the terminal status: " + sectionText.get("text").asText());
    JsonNode context = blocks.get(1);
    assertEquals("context", context.get("type").asText());
    assertEquals("#deploys", body.get("channel").asText(), "channel override passes through");
  }

  @Test
  void slackHook_missingCredentialsId_skipsAndDoesNotPost() throws Exception {
    CountingCredentialsService creds =
        new CountingCredentialsService("default", "x", "http://nope");
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(http, creds);

    NotifyHook hook = new NotifyHook();
    hook.setType("slack");
    hook.setOn(List.of("failure"));
    // no credentialsId set — dispatcher defence-in-depth skip
    dispatcher.fireStageHooks(7L, "Build", "FAILED", List.of(hook));

    Thread.sleep(100); // give any rogue POST a moment to (fail to) arrive
    assertEquals(0, creds.calls.get(), "no credential resolution attempted");
    assertTrue(deliveries.isEmpty(), "no POST when credentialsId is missing");
  }

  @Test
  void slackHook_nullCredentialsService_skipsCleanly() throws Exception {
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(http, null);

    NotifyHook hook = new NotifyHook();
    hook.setType("slack");
    hook.setOn(List.of("failure"));
    hook.setCredentialsId("slack-prod");

    // Must not throw — best-effort fail-safe.
    dispatcher.fireStageHooks(7L, "Build", "FAILED", List.of(hook));
    Thread.sleep(100);
    assertTrue(deliveries.isEmpty(), "no POST when CredentialsService is unwired");
  }

  @Test
  void slackHook_unresolvedCredential_skipsCleanly() throws Exception {
    CountingCredentialsService creds =
        new CountingCredentialsService(null, null, null); // returns empty
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(http, creds);

    NotifyHook hook = new NotifyHook();
    hook.setType("slack");
    hook.setOn(List.of("failure"));
    hook.setCredentialsId("slack-prod");

    dispatcher.fireStageHooks(7L, "Build", "FAILED", List.of(hook));
    Thread.sleep(100);
    assertEquals(1, creds.calls.get(), "resolve attempted once");
    assertTrue(deliveries.isEmpty(), "no POST when credential resolves to empty");
  }

  @Test
  void slackHook_onSuccessFilterDoesNotFireOnFailure() throws Exception {
    String slackUrl = "http://127.0.0.1:" + port + "/slack";
    CountingCredentialsService creds =
        new CountingCredentialsService("default", "slack-prod", slackUrl);
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(http, creds);

    NotifyHook hook = new NotifyHook();
    hook.setType("slack");
    hook.setOn(List.of("success"));
    hook.setCredentialsId("slack-prod");

    dispatcher.fireStageHooks(7L, "Build", "FAILED", List.of(hook));
    Thread.sleep(100);
    assertEquals(
        0, creds.calls.get(), "predicate filtering must happen BEFORE credential resolution");
    assertFalse(deliveries.iterator().hasNext());
  }

  @Test
  void slackHook_endpointReturns500_logsAndDoesNotThrow() throws Exception {
    // #1102 graceful-skip: a 5xx from the Slack endpoint must NOT propagate. Bind a second
    // context to the existing server that always answers 500.
    server.createContext(
        "/slack-fail",
        ex -> {
          ex.sendResponseHeaders(500, -1);
          ex.close();
        });
    String url = "http://127.0.0.1:" + port + "/slack-fail";
    CountingCredentialsService creds = new CountingCredentialsService("default", "slack-x", url);
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(http, creds);

    // Capture the dispatcher's logger to assert that the resolved URL is NEVER printed.
    java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(NotificationDispatcher.class.getName());
    java.util.List<String> records = new java.util.concurrent.CopyOnWriteArrayList<>();
    java.util.logging.Handler handler =
        new java.util.logging.Handler() {
          @Override
          public void publish(java.util.logging.LogRecord r) {
            records.add(
                r.getLevel()
                    + " "
                    + java.text.MessageFormat.format(
                        r.getMessage(),
                        r.getParameters() == null ? new Object[0] : r.getParameters()));
          }

          @Override
          public void flush() {}

          @Override
          public void close() {}
        };
    logger.addHandler(handler);
    try {
      NotifyHook hook = new NotifyHook();
      hook.setType("slack");
      hook.setOn(List.of("failure"));
      hook.setCredentialsId("slack-x");

      // Must not throw — best-effort contract.
      dispatcher.fireStageHooks(99L, "Build", "FAILED", List.of(hook));
      Thread.sleep(150);
    } finally {
      logger.removeHandler(handler);
    }

    String allLogs = String.join("\n", records);
    assertFalse(
        allLogs.contains(url),
        "resolved Slack webhook URL must NEVER appear in any log line; saw: " + allLogs);
    assertFalse(
        allLogs.contains("/slack-fail"), "URL path/host must not appear either; saw: " + allLogs);
    assertTrue(
        allLogs.contains("500") || allLogs.toLowerCase().contains("returned"),
        "expected a WARNING log noting the 5xx; saw: " + allLogs);
  }

  @Test
  void richBuildHook_recoveryTransition_postsBlockKitWithDeepLink() throws Exception {
    // End-to-end #1102 path: dispatcher invoked with NotificationContext flagged as a recovery.
    String slackUrl = "http://127.0.0.1:" + port + "/slack";
    CountingCredentialsService creds =
        new CountingCredentialsService("default", "slack-rec", slackUrl);
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(http, creds);

    NotifyHook hook = new NotifyHook();
    hook.setType("slack");
    hook.setOn(List.of("recovery"));
    hook.setCredentialsId("slack-rec");
    hook.setChannel("#oncall");

    NotificationContext ctx =
        NotificationContext.of(
            55L, "SUCCESS", "FAILED", "svc/api", 30_000L, null, "https://titan.test/builds/55");
    dispatcher.fireManyRich(List.of(hook), ctx);

    waitForDelivery(Duration.ofSeconds(3));
    assertEquals(1, deliveries.size(), "recovery hook should post exactly once on fail→success");
    JsonNode body = JSON.readTree(deliveries.peek());
    assertEquals("#oncall", body.get("channel").asText());
    assertTrue(body.get("text").asText().contains("RECOVERED"));
    // The actions block should contain the deep-link button.
    JsonNode blocks = body.get("blocks");
    boolean sawButton = false;
    for (JsonNode b : blocks) {
      if ("actions".equals(b.get("type").asText())) {
        sawButton =
            "https://titan.test/builds/55".equals(b.get("elements").get(0).get("url").asText());
      }
    }
    assertTrue(sawButton, "actions block must carry the deep-link button");
  }

  @Test
  void richBuildHook_recoveryHookOnPlainGreen_doesNotFire() throws Exception {
    // Plain success (no prior failure) — a `recovery`-only hook must NOT fire.
    String slackUrl = "http://127.0.0.1:" + port + "/slack";
    CountingCredentialsService creds =
        new CountingCredentialsService("default", "slack-rec", slackUrl);
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    NotificationDispatcher dispatcher = new NotificationDispatcher(http, creds);

    NotifyHook hook = new NotifyHook();
    hook.setType("slack");
    hook.setOn(List.of("recovery"));
    hook.setCredentialsId("slack-rec");

    NotificationContext ctx =
        NotificationContext.of(56L, "SUCCESS", "SUCCESS", "svc/api", 30_000L, null, null);
    dispatcher.fireManyRich(List.of(hook), ctx);

    Thread.sleep(120);
    assertEquals(
        0,
        creds.calls.get(),
        "predicate filtering must happen BEFORE credential resolution for recovery hooks too");
    assertTrue(deliveries.isEmpty(), "no POST on ordinary green for a recovery-only hook");
  }

  private void waitForDelivery(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (deliveries.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
  }

  /**
   * Hand-rolled stub: counts {@code resolvePlaintext} calls so the test can assert single-resolve
   * per dispatch, and returns the (scope, key, value) configured at construction.
   */
  private static final class CountingCredentialsService implements CredentialsService {
    final AtomicInteger calls = new AtomicInteger();
    private final String expectedScope;
    private final String expectedKey;
    private final String value;

    CountingCredentialsService(String expectedScope, String expectedKey, String value) {
      this.expectedScope = expectedScope;
      this.expectedKey = expectedKey;
      this.value = value;
    }

    @Override
    @NonNull
    public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
      calls.incrementAndGet();
      if (value == null) {
        return Optional.empty();
      }
      if (expectedScope != null && !expectedScope.equals(scope)) {
        return Optional.empty();
      }
      if (expectedKey != null && !expectedKey.equals(key)) {
        return Optional.empty();
      }
      return Optional.of(value);
    }

    // ── unused CRUD surface ────────────────────────────────────────────────
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
    public int rotateKek() {
      return 0;
    }

    @Override
    @NonNull
    public String backendName() {
      return "test-stub";
    }
  }
}
