package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.adaptiq.titan.worker.step.MaskingLogSink;
import io.adaptiq.titan.worker.step.StepExecutionContext;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.augment.CredentialsAugmenter;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test for the {@code httpRequest} step through the <strong>real worker step
 * path</strong> — the integration {@code HttpRequestStepHandlerTest} (the unit suite) deliberately
 * mocks.
 *
 * <p>Each run here builds a {@link StepExecutionContext}, runs the real {@link
 * CredentialsAugmenter} over it (so a {@code credentials:} binding lands in {@code env()} and
 * registers for masking exactly as in production — design/39), assembles the {@link StepRequest}
 * the way {@code TaskExecutor} does, wraps the log in the real {@link MaskingLogSink}, and runs the
 * handler. The counterpart is a <em>stateful</em> in-JVM API server — a miniature "releases" API —
 * so the tests are realistic multi-call workflows, not single shots.
 *
 * <p>This proves the chain the unit test fakes: {@code credentials:} → unsealed bundle → augmenter
 * → {@code env()} → {@code ${VAR}} expansion → a real authenticated HTTP call → JSON response →
 * named step outputs → fed into the next call.
 */
class HttpRequestStepE2ETest {

  private static final String TOKEN = "ghp-SEKRET-abc123";

  @TempDir Path workDir;

  private HttpServer server;
  private String baseUrl;

  /** Server-side state, so a create → publish → verify workflow is genuinely stateful. */
  private volatile String releaseState = "none";

  @BeforeEach
  void startApi() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

    // POST /releases — requires Bearer auth; creates the release, returns its JSON.
    server.createContext(
        "/releases",
        ex -> {
          if (ex.getRequestURI().getPath().equals("/releases")
              && "POST".equals(ex.getRequestMethod())) {
            if (!authorized(ex)) {
              respond(ex, 401, "{\"message\":\"Bad credentials\"}");
              return;
            }
            ex.getRequestBody().readAllBytes(); // drain the jsonBody
            releaseState = "draft";
            respond(
                ex,
                201,
                "{\"id\":\"rel-7\",\"state\":\"draft\","
                    + "\"upload_url\":\""
                    + baseUrl
                    + "/releases/rel-7/assets\"}");
            return;
          }
          // GET /releases/rel-7  — read the resource back.
          if ("GET".equals(ex.getRequestMethod())) {
            respond(ex, 200, "{\"id\":\"rel-7\",\"state\":\"" + releaseState + "\"}");
            return;
          }
          // POST /releases/rel-7/publish — flip the state.
          if (ex.getRequestURI().getPath().endsWith("/publish")) {
            if (!authorized(ex)) {
              respond(ex, 401, "{\"message\":\"Bad credentials\"}");
              return;
            }
            releaseState = "published";
            respond(ex, 200, "{\"id\":\"rel-7\",\"state\":\"published\"}");
            return;
          }
          respond(ex, 404, "{\"message\":\"not found\"}");
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopApi() {
    server.stop(0);
  }

  private static boolean authorized(com.sun.net.httpserver.HttpExchange ex) {
    return ("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"));
  }

  private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(code, bytes.length);
    try (OutputStream os = ex.getResponseBody()) {
      os.write(bytes);
    }
    ex.close();
  }

  /** A credential bundle as the controller produces it for {@code credentials: [{type:string}]}. */
  private static Map<String, Object> tokenBundle() {
    return Map.of("env", Map.of("GH_TOKEN", TOKEN), "maskSecrets", List.of(TOKEN));
  }

  private E2EResult result;

  /**
   * Run one {@code httpRequest} step through the real worker path: context → CredentialsAugmenter →
   * StepRequest (masking log) → handler.
   */
  private E2EResult runStep(Map<String, Object> args, Map<String, Object> credentialBundle)
      throws Exception {
    Map<String, Object> arguments = new LinkedHashMap<>(args);
    Map<String, String> env = new HashMap<>();
    StepExecutionContext ctx =
        new StepExecutionContext(
            "httpRequest", arguments, env, workDir, 1L, "node-1", credentialBundle);
    new CredentialsAugmenter().augment(ctx); // the real augmenter — env merge + mask register

    StepHandlerTck.CapturingLog rawLog = new StepHandlerTck.CapturingLog();
    StepHandlerTck.CapturingOutputs outputs = new StepHandlerTck.CapturingOutputs();
    StepRequest request =
        new StepRequest(
            ctx.descriptorId(),
            ctx.arguments(),
            ctx.workspace(),
            ctx.env(),
            ctx.buildId(),
            ctx.nodeId(),
            null,
            new LocalProcessExecutor(),
            MaskingLogSink.wrap(rawLog, ctx.maskValues()),
            outputs);
    StepResult stepResult = new HttpRequestStepHandler().execute(request);
    return new E2EResult(stepResult, outputs, rawLog, ctx);
  }

  private record E2EResult(
      StepResult result,
      StepHandlerTck.CapturingOutputs outputs,
      StepHandlerTck.CapturingLog log,
      StepExecutionContext ctx) {}

  // ── tests ─────────────────────────────────────────────────────────────

  @Test
  void authenticatedCreate_resolvesTheSecretAndExtractsTheReleaseId() throws Exception {
    result =
        runStep(
            Map.of(
                "url",
                baseUrl + "/releases",
                "method",
                "POST",
                "headers",
                Map.of("Authorization", "Bearer ${GH_TOKEN}"),
                "jsonBody",
                Map.of("tag_name", "v2.0.0", "draft", true),
                "validResponseCodes",
                "201",
                "outputs",
                Map.of("releaseId", "id", "uploadUrl", "upload_url")),
            tokenBundle());

    // The credentials: bundle → augmenter → env() → ${GH_TOKEN} expansion → the server
    // accepted the call. A 201 only happens when the real token reached the Authorization
    // header — the whole credential chain the unit test mocks.
    assertTrue(result.result().isSuccess(), result.result().message());
    assertEquals(201, result.outputs().map.get("status"));
    // The JSON response became named pipeline state.
    assertEquals("rel-7", result.outputs().map.get("releaseId"));
    assertEquals(baseUrl + "/releases/rel-7/assets", result.outputs().map.get("uploadUrl"));
  }

  @Test
  void theSecretIsRegisteredForMaskingByTheRealAugmenter() throws Exception {
    result =
        runStep(
            Map.of(
                "url",
                baseUrl + "/releases",
                "method",
                "POST",
                "headers",
                Map.of("Authorization", "Bearer ${GH_TOKEN}"),
                "validResponseCodes",
                "201"),
            tokenBundle());

    // The CredentialsAugmenter registered the secret; the MaskingLogSink redacts it.
    assertTrue(
        result.ctx().maskValues().contains(TOKEN),
        "the augmenter must register the credential value for masking");
    result.log().lines.clear();
    // Anything written to this step's log with the secret in it comes out masked.
    StepHandlerTck.CapturingLog raw = new StepHandlerTck.CapturingLog();
    MaskingLogSink.wrap(raw, result.ctx().maskValues()).system("calling api with token " + TOKEN);
    assertFalse(raw.text().contains(TOKEN), "the secret must not survive into the log");
    assertTrue(raw.text().contains("****"), "the secret must be masked: " + raw.text());
  }

  @Test
  void withoutTheCredential_theCallIsRejected() throws Exception {
    // No credential bundle — ${GH_TOKEN} has nothing to resolve to, the server returns 401,
    // and the step fails. Proves the auth actually gates the call.
    result =
        runStep(
            Map.of(
                "url",
                baseUrl + "/releases",
                "method",
                "POST",
                "headers",
                Map.of("Authorization", "Bearer ${GH_TOKEN}"),
                "validResponseCodes",
                "201"),
            Map.of()); // empty bundle

    assertFalse(result.result().isSuccess());
    assertEquals(401, result.outputs().map.get("status"));
  }

  @Test
  void anExtractedOutputThreadsIntoTheNextCall() throws Exception {
    // Stage 1: create the release, extract its id.
    E2EResult create =
        runStep(
            Map.of(
                "url",
                baseUrl + "/releases",
                "method",
                "POST",
                "headers",
                Map.of("Authorization", "Bearer ${GH_TOKEN}"),
                "validResponseCodes",
                "201",
                "outputs",
                Map.of("releaseId", "id")),
            tokenBundle());
    assertTrue(create.result().isSuccess(), create.result().message());
    String releaseId = (String) create.outputs().map.get("releaseId");

    // Stage 2: GET that release by the id stage 1 produced — this is what the orchestrator's
    // ${{ steps['Create'].outputs.releaseId }} resolution feeds into a downstream step.
    result =
        runStep(
            Map.of("url", baseUrl + "/releases/" + releaseId, "outputs", Map.of("state", "state")),
            Map.of());
    assertTrue(result.result().isSuccess(), result.result().message());
    assertEquals("draft", result.outputs().map.get("state"));
  }

  @Test
  void aRealisticCreatePublishVerifyWorkflow() throws Exception {
    // create → publish → verify, three real authenticated calls against stateful server state.
    assertTrue(
        runStep(
                Map.of(
                    "url",
                    baseUrl + "/releases",
                    "method",
                    "POST",
                    "headers",
                    Map.of("Authorization", "Bearer ${GH_TOKEN}"),
                    "validResponseCodes",
                    "201"),
                tokenBundle())
            .result()
            .isSuccess());

    assertTrue(
        runStep(
                Map.of(
                    "url",
                    baseUrl + "/releases/rel-7/publish",
                    "method",
                    "POST",
                    "headers",
                    Map.of("Authorization", "Bearer ${GH_TOKEN}")),
                tokenBundle())
            .result()
            .isSuccess());

    result =
        runStep(
            Map.of("url", baseUrl + "/releases/rel-7", "outputs", Map.of("state", "state")),
            Map.of());
    assertTrue(result.result().isSuccess(), result.result().message());
    assertEquals(
        "published",
        result.outputs().map.get("state"),
        "the workflow's state change must be observable on read-back");
  }
}
