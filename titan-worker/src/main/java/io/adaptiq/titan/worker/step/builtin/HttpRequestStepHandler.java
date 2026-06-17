package io.adaptiq.titan.worker.step.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * The built-in {@code httpRequest} step — makes one HTTP(S) request and turns the response into
 * pipeline state (design/50).
 *
 * <p>A first-class step for what pipelines constantly do — call an API, fire a webhook, gate on a
 * remote check — instead of a fragile {@code sh: curl …}: a structured request, secrets that never
 * touch the YAML or the queue, an asserted status, and the response surfaced as <em>named step
 * outputs</em> that later stages read with {@code ${{ steps['…'].outputs.… }}}.
 *
 * <h2>Secrets in headers</h2>
 *
 * <p>A {@code StepHandler} must not resolve a credential itself (design/32 §6.3). Instead the
 * {@code credentials:} scope seals the secret into the task payload on the controller, the worker
 * unseals it into {@link StepRequest#env()}, and this handler expands {@code ${VAR}} references in
 * the {@code url}, {@code headers}, {@code body} and {@code jsonBody} against that environment at
 * execution time. So {@code Authorization: "Bearer ${TOKEN}"} + a {@code credentials:} binding
 * gives an authenticated request whose secret is encrypted at rest, never in the arguments, and
 * masked in the log.
 *
 * <h2>Response as pipeline state</h2>
 *
 * <p>The step always publishes {@code status} and a (size-capped) {@code body}. An {@code outputs:}
 * map — {@code outputName: json.path} — extracts fields from a JSON response and publishes each as
 * its own named output, so {@code httpRequest} becomes a state source: create a resource, capture
 * its id, use it downstream.
 *
 * <p>Runs in the worker JVM via the JDK {@link HttpClient}; the step {@code image:} is ignored — an
 * HTTP call has no process to containerise (design/50 D2). Idempotency (design/30): {@code GET} is
 * safe to re-run; a reaped {@code POST}/{@code PUT}/{@code DELETE} is the author's contract.
 */
public final class HttpRequestStepHandler implements StepHandler {

  /** HTTP methods the step accepts. */
  private static final List<String> METHODS =
      List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");

  /** Methods that never carry a request body. */
  private static final Set<String> BODYLESS = Set.of("GET", "HEAD");

  /** Default status-code acceptance — informational through redirect. */
  private static final String DEFAULT_VALID_CODES = "100:399";

  /** Default overall request timeout — not infinite, so a hung call cannot wedge an executor. */
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /**
   * Cap on the response body captured into the {@code body} output / JSON-extracted from. A bounded
   * in-memory capture is what makes publishing the body safe — a hostile or huge response cannot
   * OOM the worker. Larger bodies still stream in full to {@code outputFile}.
   */
  private static final int MAX_OUTPUT_BODY_BYTES = 1024 * 1024;

  /** {@code ${VAR}} env-reference token. */
  private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

  /** One step of a friendly JSON path — a field name or an {@code [index]}. */
  private static final Pattern JSON_PATH_TOKEN = Pattern.compile("([^\\[\\].]+)|\\[(\\d+)\\]");

  private static final ObjectMapper JSON = new ObjectMapper();

  @Override
  public String descriptorId() {
    return "httpRequest";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "httpRequest",
        "HTTP request",
        "Makes one HTTP(S) request. ${VAR} in the url/headers/body is expanded from the "
            + "step environment, so a credentials:-bound secret can authenticate the "
            + "call. Publishes the response as step outputs.",
        List.of(
            ParamSpec.required("url", "string", "Request URL (http/https only)."),
            new ParamSpec("method", "string", false, "HTTP method (default GET).", METHODS),
            ParamSpec.optional("headers", "object", "Map of request headers."),
            ParamSpec.optional("body", "string", "Inline request body."),
            ParamSpec.optional(
                "jsonBody", "object", "An object serialised to a JSON request body."),
            ParamSpec.optional(
                "bodyFile", "string", "Workspace-relative file used as the body (overrides body)."),
            ParamSpec.optional(
                "contentType",
                "string",
                "Sets the Content-Type header (defaults to application/json "
                    + "when jsonBody is used)."),
            ParamSpec.optional(
                "validResponseCodes",
                "string",
                "Accepted status codes — e.g. '200' or '201,301:303' " + "(default '100:399')."),
            ParamSpec.optional(
                "outputFile",
                "string",
                "Workspace-relative file to stream the response body into."),
            ParamSpec.optional(
                "outputs",
                "object",
                "Map of outputName: json.path — extracts fields from a JSON "
                    + "response and publishes each as a named step output."),
            ParamSpec.optional(
                "timeoutSeconds", "number", "Overall request timeout in seconds (default 30)."),
            ParamSpec.optional(
                "insecureTls", "boolean", "Skip TLS certificate validation (default false).")),
        // design/42 §4.6: the scalar shorthand `httpRequest: <url>` resolves to `url`.
        "url");
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    Map<String, String> env = request.env();

    String rawUrl = request.argString("url");
    if (rawUrl == null) {
      rawUrl = request.argString("value"); // scalar shorthand `httpRequest: <url>`
    }
    if (rawUrl == null || rawUrl.isBlank()) {
      return StepResult.failed("httpRequest step has no 'url'");
    }
    String url = expandEnv(rawUrl.trim(), env);
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException e) {
      return StepResult.failed("httpRequest: malformed url");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("http") && !scheme.equals("https")) {
      return StepResult.failed("httpRequest: only http/https URLs are allowed");
    }

    String method = request.argString("method", "GET").trim().toUpperCase(Locale.ROOT);
    if (!METHODS.contains(method)) {
      return StepResult.failed("httpRequest: unsupported method '" + method + "'");
    }
    int timeoutSeconds =
        parsePositiveInt(request.argString("timeoutSeconds"), DEFAULT_TIMEOUT_SECONDS);
    boolean insecureTls = request.argBoolean("insecureTls", false);

    // ── request body: bodyFile > body > jsonBody ──────────────────────
    HttpRequest.BodyPublisher body;
    String defaultContentType = null;
    String bodyFile = request.argString("bodyFile");
    Object jsonBody = request.arguments().get("jsonBody");
    if (BODYLESS.contains(method)) {
      body = HttpRequest.BodyPublishers.noBody();
    } else if (bodyFile != null && !bodyFile.isBlank()) {
      Path src = confineToWorkspace(request.workDir(), bodyFile);
      if (src == null) {
        return StepResult.failed("httpRequest: bodyFile escapes the workspace: " + bodyFile);
      }
      if (!Files.isRegularFile(src)) {
        return StepResult.failed("httpRequest: bodyFile not found: " + bodyFile);
      }
      body = HttpRequest.BodyPublishers.ofFile(src);
    } else if (request.argString("body") != null) {
      body = HttpRequest.BodyPublishers.ofString(expandEnv(request.argString("body"), env));
    } else if (jsonBody != null) {
      String json;
      try {
        json = expandEnv(JSON.writeValueAsString(jsonBody), env);
      } catch (IOException e) {
        return StepResult.failed("httpRequest: jsonBody is not serialisable: " + e.getMessage());
      }
      body = HttpRequest.BodyPublishers.ofString(json);
      defaultContentType = "application/json";
    } else {
      body = HttpRequest.BodyPublishers.noBody();
    }

    HttpRequest.Builder rb =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .method(method, body);

    String contentType = request.argString("contentType");
    if (contentType != null && !contentType.isBlank()) {
      rb.header("Content-Type", contentType.trim());
    } else if (defaultContentType != null) {
      rb.header("Content-Type", defaultContentType);
    }
    // Headers — ${VAR}-expanded, then CR/LF-rejected (header injection) before the client.
    Object rawHeaders = request.arguments().get("headers");
    if (rawHeaders instanceof Map<?, ?> headers) {
      for (Map.Entry<?, ?> e : headers.entrySet()) {
        String name = String.valueOf(e.getKey());
        String value = expandEnv(e.getValue() == null ? "" : String.valueOf(e.getValue()), env);
        if (hasControlChars(name) || hasControlChars(value)) {
          return StepResult.failed("httpRequest: illegal CR/LF in header '" + name + "'");
        }
        try {
          rb.header(name, value);
        } catch (IllegalArgumentException ex) {
          return StepResult.failed(
              "httpRequest: header '" + name + "' rejected: " + ex.getMessage());
        }
      }
    }

    Path outputFile = null;
    String outArg = request.argString("outputFile");
    if (outArg != null && !outArg.isBlank()) {
      outputFile = confineToWorkspace(request.workDir(), outArg);
      if (outputFile == null) {
        return StepResult.failed("httpRequest: outputFile escapes the workspace: " + outArg);
      }
    }

    HttpClient.Builder cb =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
            .followRedirects(HttpClient.Redirect.NORMAL);
    if (insecureTls) {
      request
          .log()
          .system(
              "httpRequest: WARNING insecureTls=true — "
                  + "TLS certificate validation is disabled for this request");
      cb.sslContext(trustAllSslContext());
    }

    // ── send + capture the response ───────────────────────────────────
    int status;
    Capture captured;
    long startMillis = System.currentTimeMillis();
    // Not try-with-resources: HttpClient is AutoCloseable only from Java 21 and this module
    // compiles at release 17. A short-lived client is GC-reclaimed.
    HttpClient client = cb.build();
    try {
      HttpResponse<InputStream> response =
          client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
      status = response.statusCode();
      captured = captureBody(response.body(), outputFile);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return StepResult.failed("httpRequest: interrupted calling " + uri);
    } catch (IOException e) {
      return StepResult.failed("httpRequest: " + method + " " + uri + " failed: " + e.getMessage());
    }
    long elapsed = System.currentTimeMillis() - startMillis;

    // ── publish the response as pipeline state ─────────────────────────
    request.outputs().put("status", status);
    request.outputs().put("body", captured.text());
    request.outputs().put("bodyTruncated", captured.truncated());
    request
        .log()
        .system("httpRequest: " + method + " " + uri + " -> " + status + " (" + elapsed + "ms)");

    String validCodes = request.argString("validResponseCodes", DEFAULT_VALID_CODES);
    Boolean accepted = statusMatches(status, validCodes);
    if (accepted == null) {
      return StepResult.failed("httpRequest: malformed validResponseCodes '" + validCodes + "'");
    }
    if (!accepted) {
      return StepResult.failed(
          "httpRequest: "
              + method
              + " "
              + uri
              + " returned status "
              + status
              + ", expected "
              + validCodes);
    }

    // The status is good — extract the declared JSON outputs, if any.
    Object outputsSpec = request.arguments().get("outputs");
    if (outputsSpec instanceof Map<?, ?> spec && !spec.isEmpty()) {
      JsonNode root;
      try {
        root = JSON.readTree(captured.text());
      } catch (IOException e) {
        return StepResult.failed("httpRequest: response is not JSON — cannot extract 'outputs'");
      }
      if (root == null || root.isMissingNode()) {
        return StepResult.failed("httpRequest: empty response body — cannot extract 'outputs'");
      }
      for (Map.Entry<?, ?> e : spec.entrySet()) {
        String outName = String.valueOf(e.getKey());
        String path = String.valueOf(e.getValue());
        JsonNode found = navigateJson(root, path);
        if (found == null || found.isMissingNode()) {
          return StepResult.failed(
              "httpRequest: outputs path '"
                  + path
                  + "' (for output '"
                  + outName
                  + "') not found in the JSON response");
        }
        request.outputs().put(outName, found.isValueNode() ? found.asText() : found.toString());
      }
    }
    return StepResult.success();
  }

  /** The captured response body — text up to the cap, plus whether bytes were dropped. */
  private record Capture(String text, boolean truncated) {}

  /**
   * Read the response stream once: stream it in full to {@code outputFile} when given, and capture
   * up to {@link #MAX_OUTPUT_BODY_BYTES} into memory for the {@code body} output / JSON extraction.
   * Memory is bounded regardless of the response size.
   */
  private static Capture captureBody(InputStream in, Path outputFile) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    long total = 0;
    OutputStream fileOut = null;
    try {
      if (outputFile != null) {
        Path parent = outputFile.getParent();
        if (parent != null) {
          Files.createDirectories(parent);
        }
        fileOut = Files.newOutputStream(outputFile);
      }
      byte[] chunk = new byte[8192];
      int n;
      while ((n = in.read(chunk)) != -1) {
        total += n;
        if (fileOut != null) {
          fileOut.write(chunk, 0, n);
        }
        int room = MAX_OUTPUT_BODY_BYTES - buffer.size();
        if (room > 0) {
          buffer.write(chunk, 0, Math.min(n, room));
        }
      }
    } finally {
      in.close();
      if (fileOut != null) {
        fileOut.close();
      }
    }
    return new Capture(buffer.toString(StandardCharsets.UTF_8), total > buffer.size());
  }

  /** Substitute every {@code ${VAR}} with {@code env[VAR]}; an unknown var is left verbatim. */
  private static String expandEnv(String template, Map<String, String> env) {
    if (template == null || template.indexOf("${") < 0) {
      return template;
    }
    Matcher m = ENV_REF.matcher(template);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = env.get(m.group(1));
      m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
    }
    m.appendTail(out);
    return out.toString();
  }

  /**
   * Navigate a friendly JSON path — {@code data.id}, {@code items[0].name} — over a parsed tree.
   * Returns {@code null} / a missing node when any step is absent.
   */
  private static JsonNode navigateJson(JsonNode root, String path) {
    JsonNode current = root;
    Matcher m = JSON_PATH_TOKEN.matcher(path);
    while (m.find() && current != null && !current.isMissingNode()) {
      if (m.group(1) != null) {
        current = current.get(m.group(1));
      } else {
        current = current.get(Integer.parseInt(m.group(2)));
      }
    }
    return current;
  }

  /**
   * Resolve {@code relative} under {@code workDir} and confirm it stays inside it. Returns {@code
   * null} when the path escapes the workspace.
   */
  private static Path confineToWorkspace(Path workDir, String relative) {
    Path resolved = workDir.resolve(relative).normalize();
    return resolved.startsWith(workDir.normalize()) ? resolved : null;
  }

  private static boolean hasControlChars(String s) {
    return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0;
  }

  private static int parsePositiveInt(String raw, int fallback) {
    if (raw == null || raw.isBlank()) {
      return fallback;
    }
    try {
      int v = Integer.parseInt(raw.trim());
      return v > 0 ? v : fallback;
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * Match {@code status} against a spec — comma-separated single codes and {@code from:to} ranges.
   * Returns {@code null} when the spec is malformed.
   */
  private static Boolean statusMatches(int status, String spec) {
    boolean matched = false;
    for (String token : spec.split(",")) {
      String t = token.trim();
      if (t.isEmpty()) {
        continue;
      }
      try {
        int colon = t.indexOf(':');
        if (colon >= 0) {
          int lo = Integer.parseInt(t.substring(0, colon).trim());
          int hi = Integer.parseInt(t.substring(colon + 1).trim());
          if (status >= lo && status <= hi) {
            matched = true;
          }
        } else if (status == Integer.parseInt(t)) {
          matched = true;
        }
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return matched;
  }

  /** An {@link SSLContext} that trusts any server certificate — only when {@code insecureTls}. */
  private static SSLContext trustAllSslContext() throws Exception {
    SSLContext ctx = SSLContext.getInstance("TLS");
    TrustManager trustAll =
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType) {}

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) {}

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
          }
        };
    ctx.init(null, new TrustManager[] {trustAll}, new SecureRandom());
    return ctx;
  }
}
