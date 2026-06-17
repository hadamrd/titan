package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpRequestStepHandler} — the shared TCK plus {@code httpRequest}-specifics (design/50):
 * {@code ${VAR}} secret-header expansion, {@code jsonBody}, and JSON response extraction into named
 * step outputs.
 *
 * <p>Exercised against an in-JVM {@link HttpServer} test double: fast, deterministic, no Docker.
 */
class HttpRequestStepHandlerTest extends StepHandlerTck {

  private HttpServer server;
  private String baseUrl;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ok", ex -> respond(ex, 200, "ok-body"));
    // Echoes the request body back — for POST / jsonBody / bodyFile assertions.
    server.createContext(
        "/echo",
        ex -> {
          byte[] in = ex.getRequestBody().readAllBytes();
          ex.sendResponseHeaders(200, in.length == 0 ? -1 : in.length);
          if (in.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
              os.write(in);
            }
          }
          ex.close();
        });
    // Echoes the received Authorization header back as the body.
    server.createContext(
        "/echo-auth",
        ex -> {
          String auth = ex.getRequestHeaders().getFirst("Authorization");
          respond(ex, 200, auth == null ? "none" : auth);
        });
    // A JSON document for the extraction tests.
    server.createContext(
        "/json",
        ex ->
            respond(
                ex,
                200,
                "{\"data\":{\"id\":\"r-42\"},\"items\":[{\"name\":\"first\"}],\"ok\":true}"));
    server.createContext("/notfound", ex -> respond(ex, 404, "nope"));
    server.createContext(
        "/redirect",
        ex -> {
          ex.getResponseHeaders().add("Location", baseUrl + "/ok");
          respond(ex, 302, "");
        });
    // A body larger than the 1 MiB output cap.
    server.createContext(
        "/big",
        ex -> {
          byte[] big = new byte[1024 * 1024 + 4096];
          Arrays.fill(big, (byte) 'x');
          ex.sendResponseHeaders(200, big.length);
          try (OutputStream os = ex.getResponseBody()) {
            os.write(big);
          }
          ex.close();
        });
    server.createContext(
        "/slow",
        ex -> {
          try {
            Thread.sleep(5_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          respond(ex, 200, "late");
        });
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static void respond(com.sun.net.httpserver.HttpExchange ex, int code, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
    if (bytes.length > 0) {
      try (OutputStream os = ex.getResponseBody()) {
        os.write(bytes);
      }
    }
    ex.close();
  }

  // ── TCK wiring ────────────────────────────────────────────────────────

  @Override
  protected StepHandler newHandler() {
    return new HttpRequestStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("url", baseUrl + "/ok");
  }

  private Result run(Map<String, Object> args) throws Exception {
    return run(args, Map.of());
  }

  /** Build a request with sinks the test owns + a chosen step environment. */
  private Result run(Map<String, Object> args, Map<String, String> env) throws Exception {
    StepHandlerTck.CapturingLog log = new StepHandlerTck.CapturingLog();
    StepHandlerTck.CapturingOutputs outputs = new StepHandlerTck.CapturingOutputs();
    StepRequest req =
        new StepRequest(
            "httpRequest",
            args,
            workDir,
            env,
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            log,
            outputs);
    StepResult result = new HttpRequestStepHandler().execute(req);
    return new Result(result, outputs, log);
  }

  private record Result(
      StepResult result,
      StepHandlerTck.CapturingOutputs outputs,
      StepHandlerTck.CapturingLog log) {}

  // ── request basics ────────────────────────────────────────────────────

  @Test
  void getPublishesStatusAndBodyOutputs() throws Exception {
    Result r = run(Map.of("url", baseUrl + "/ok"));
    assertTrue(r.result().isSuccess());
    assertEquals(200, r.outputs().map.get("status"));
    assertEquals("ok-body", r.outputs().map.get("body"));
    assertEquals(Boolean.FALSE, r.outputs().map.get("bodyTruncated"));
  }

  @Test
  void scalarShorthandUrlWorks() throws Exception {
    assertTrue(run(Map.of("value", baseUrl + "/ok")).result().isSuccess());
  }

  @Test
  void statusOutsideValidCodesFails() throws Exception {
    Result r = run(Map.of("url", baseUrl + "/notfound"));
    assertFalse(r.result().isSuccess());
    assertEquals(404, r.outputs().map.get("status"));
  }

  @Test
  void validResponseCodesCanAcceptA404() throws Exception {
    assertTrue(
        run(Map.of("url", baseUrl + "/notfound", "validResponseCodes", "404"))
            .result()
            .isSuccess());
  }

  @Test
  void redirectIsFollowed() throws Exception {
    assertTrue(run(Map.of("url", baseUrl + "/redirect")).result().isSuccess());
  }

  // ── secrets: ${VAR} expansion from the step environment ───────────────

  @Test
  void secretHeaderIsExpandedFromTheEnvironment() throws Exception {
    // The credentials: scope binds the secret into env(); the handler expands ${TOKEN}.
    Result r =
        run(
            Map.of(
                "url",
                baseUrl + "/echo-auth",
                "headers",
                Map.of("Authorization", "Bearer ${TOKEN}")),
            Map.of("TOKEN", "s3cr3t-value"));
    assertTrue(r.result().isSuccess());
    assertEquals(
        "Bearer s3cr3t-value",
        r.outputs().map.get("body"),
        "the server must have received the resolved secret, not the literal ${TOKEN}");
  }

  @Test
  void envIsExpandedInTheUrl() throws Exception {
    Result r = run(Map.of("url", baseUrl + "/${ENDPOINT}"), Map.of("ENDPOINT", "ok"));
    assertTrue(r.result().isSuccess());
  }

  // ── request body: inline, jsonBody, bodyFile ──────────────────────────

  @Test
  void jsonBodyIsSerialisedAndSent() throws Exception {
    Result r =
        run(
            Map.of(
                "url",
                baseUrl + "/echo",
                "method",
                "POST",
                "jsonBody",
                Map.of("tag", "v1", "draft", true),
                "outputFile",
                "sent.json"));
    assertTrue(r.result().isSuccess());
    String sent = Files.readString(workDir.resolve("sent.json"));
    assertTrue(sent.contains("\"tag\":\"v1\""), sent);
    assertTrue(sent.contains("\"draft\":true"), sent);
  }

  @Test
  void inlineBodyIsSent() throws Exception {
    Result r =
        run(
            Map.of(
                "url",
                baseUrl + "/echo",
                "method",
                "POST",
                "body",
                "ping",
                "outputFile",
                "out.txt"));
    assertTrue(r.result().isSuccess());
    assertEquals("ping", Files.readString(workDir.resolve("out.txt")));
  }

  @Test
  void bodyFileIsSentAsTheRequestBody() throws Exception {
    Files.writeString(workDir.resolve("payload.json"), "{\"k\":\"v\"}");
    Result r =
        run(
            Map.of(
                "url",
                baseUrl + "/echo",
                "method",
                "PUT",
                "bodyFile",
                "payload.json",
                "outputFile",
                "out.txt"));
    assertTrue(r.result().isSuccess());
    assertEquals("{\"k\":\"v\"}", Files.readString(workDir.resolve("out.txt")));
  }

  // ── response as pipeline state: JSON extraction into named outputs ────

  @Test
  void jsonOutputsAreExtractedIntoNamedStepOutputs() throws Exception {
    Result r =
        run(
            Map.of(
                "url",
                baseUrl + "/json",
                "outputs",
                Map.of(
                    "releaseId", "data.id",
                    "firstItem", "items[0].name",
                    "flag", "ok")));
    assertTrue(r.result().isSuccess(), r.result().message());
    assertEquals("r-42", r.outputs().map.get("releaseId"));
    assertEquals("first", r.outputs().map.get("firstItem"));
    assertEquals("true", r.outputs().map.get("flag"));
  }

  @Test
  void aMissingJsonOutputPathFailsTheStep() throws Exception {
    Result r = run(Map.of("url", baseUrl + "/json", "outputs", Map.of("missing", "data.nope")));
    assertFalse(r.result().isSuccess());
    assertTrue(r.result().message().contains("not found"), r.result().message());
  }

  @Test
  void outputsExtractionOnANonJsonResponseFails() throws Exception {
    Result r = run(Map.of("url", baseUrl + "/ok", "outputs", Map.of("x", "a")));
    assertFalse(r.result().isSuccess());
    assertTrue(r.result().message().contains("not JSON"), r.result().message());
  }

  // ── bounds + guards ───────────────────────────────────────────────────

  @Test
  void aLargeBodyIsTruncatedInTheOutputButStreamedWholeToTheFile() throws Exception {
    Result r = run(Map.of("url", baseUrl + "/big", "outputFile", "big.bin"));
    assertTrue(r.result().isSuccess());
    assertEquals(Boolean.TRUE, r.outputs().map.get("bodyTruncated"));
    // The output variable is capped at 1 MiB...
    assertEquals(1024 * 1024, ((String) r.outputs().map.get("body")).length());
    // ...but the file on disk has the whole response.
    assertEquals(1024 * 1024 + 4096, Files.size(workDir.resolve("big.bin")));
  }

  @Test
  void headerWithCrLfIsRejected() throws Exception {
    Result r =
        run(Map.of("url", baseUrl + "/ok", "headers", Map.of("X-Bad", "value\r\nInjected: 1")));
    assertFalse(r.result().isSuccess());
    assertTrue(r.result().message().contains("CR/LF"), r.result().message());
  }

  @Test
  void nonHttpSchemeIsRejected() throws Exception {
    Result r = run(Map.of("url", "ftp://example.com/x"));
    assertFalse(r.result().isSuccess());
    assertTrue(r.result().message().contains("http/https"), r.result().message());
  }

  @Test
  void bodyFileEscapingTheWorkspaceIsRejected() throws Exception {
    Result r =
        run(Map.of("url", baseUrl + "/echo", "method", "POST", "bodyFile", "../../etc/passwd"));
    assertFalse(r.result().isSuccess());
    assertTrue(r.result().message().contains("escapes the workspace"), r.result().message());
  }

  @Test
  void timeoutFailsTheStep() throws Exception {
    assertFalse(run(Map.of("url", baseUrl + "/slow", "timeoutSeconds", "1")).result().isSuccess());
  }

  @Test
  void missingUrlFails() throws Exception {
    assertFalse(run(Map.of()).result().isSuccess());
  }
}
