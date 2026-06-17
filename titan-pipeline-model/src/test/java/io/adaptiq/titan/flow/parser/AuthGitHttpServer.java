package io.adaptiq.titan.flow.parser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A test fixture: a minimal HTTP server that serves a git repository over the git <em>smart</em>
 * protocol and <strong>requires HTTP Basic authentication</strong>. It exists so the {@code
 * LibraryFetcher} authentication tests (design/40 §5) exercise the genuine {@code GIT_ASKPASS}
 * credential path that a worker uses against a private remote — no mocking: a real {@code git
 * fetch} over HTTP, a real 401 challenge, a real password supplied by the askpass helper.
 *
 * <p>It proxies every request to the {@code git http-backend} CGI (shipped with git) after checking
 * the {@code Authorization} header. A request with a missing or wrong credential gets a {@code 401
 * WWW-Authenticate: Basic} — exactly what makes git invoke {@code GIT_ASKPASS}; the username is
 * ignored (token auth takes any username) and only the password — the token — is checked.
 *
 * <p>Self-skipping: {@link #isSupported()} reports whether {@code git http-backend} is locatable; a
 * test guards on it so a git build without the CGI does not fail the suite.
 */
final class AuthGitHttpServer implements AutoCloseable {

  /** The token a fetch must present as the HTTP Basic password to be served. */
  static final String EXPECTED_TOKEN = "s3cr3t-ci-token-value";

  private final HttpServer server;
  private final int port;
  private volatile int authAttempts;
  private volatile int servedRequests;

  private AuthGitHttpServer(HttpServer server, int port) {
    this.server = server;
    this.port = port;
  }

  /** True when {@code git http-backend} can be located — the fixture needs it. */
  static boolean isSupported() {
    return httpBackend() != null;
  }

  /**
   * Start a server fronting {@code gitProjectRoot} (the directory whose immediate children are
   * bare/non-bare git repos). The repository {@code <name>} is then reachable at {@code
   * http://127.0.0.1:<port>/<name>}.
   */
  static AuthGitHttpServer start(Path gitProjectRoot) throws IOException {
    String backend = httpBackend();
    if (backend == null) {
      throw new IllegalStateException("git http-backend not available");
    }
    HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    int port = http.getAddress().getPort();
    AuthGitHttpServer fixture = new AuthGitHttpServer(http, port);
    http.createContext("/", exchange -> fixture.handle(exchange, backend, gitProjectRoot));
    http.setExecutor(Executors.newCachedThreadPool());
    http.start();
    return fixture;
  }

  /** The base URL — append {@code /<repo-name>} for a clone URL. */
  String baseUrl() {
    return "http://127.0.0.1:" + port;
  }

  /** How many times a request arrived carrying an {@code Authorization} header. */
  int authAttempts() {
    return authAttempts;
  }

  /** How many requests were authenticated and proxied through to git http-backend. */
  int servedRequests() {
    return servedRequests;
  }

  @Override
  public void close() {
    server.stop(0);
  }

  private void handle(HttpExchange exchange, String backend, Path projectRoot) throws IOException {
    String auth = exchange.getRequestHeaders().getFirst("Authorization");
    if (auth != null) {
      authAttempts++;
    }
    if (!isAuthorized(auth)) {
      exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"titan-test\"");
      byte[] body = "authentication required".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(401, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
      return;
    }
    servedRequests++;
    proxyToGitBackend(exchange, backend, projectRoot);
  }

  /** Basic-auth check: any username, the password must equal {@link #EXPECTED_TOKEN}. */
  private static boolean isAuthorized(String authHeader) {
    if (authHeader == null || !authHeader.startsWith("Basic ")) {
      return false;
    }
    String decoded =
        new String(
            Base64.getDecoder().decode(authHeader.substring("Basic ".length())),
            StandardCharsets.UTF_8);
    int colon = decoded.indexOf(':');
    if (colon < 0) {
      return false;
    }
    String password = decoded.substring(colon + 1);
    return EXPECTED_TOKEN.equals(password);
  }

  /** Run git http-backend as a CGI for this request and stream its response back. */
  private void proxyToGitBackend(HttpExchange exchange, String backend, Path projectRoot)
      throws IOException {
    Map<String, String> env = new HashMap<>(System.getenv());
    env.put("GIT_PROJECT_ROOT", projectRoot.toAbsolutePath().toString());
    env.put("GIT_HTTP_EXPORT_ALL", "1");
    env.put("REQUEST_METHOD", exchange.getRequestMethod());
    env.put("PATH_INFO", exchange.getRequestURI().getPath());
    String query = exchange.getRequestURI().getRawQuery();
    env.put("QUERY_STRING", query == null ? "" : query);
    String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
    if (contentType != null) {
      env.put("CONTENT_TYPE", contentType);
    }
    String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
    if (contentLength != null) {
      env.put("CONTENT_LENGTH", contentLength);
    }
    env.put("REMOTE_USER", "titan-test");
    env.put("REMOTE_ADDR", "127.0.0.1");

    ProcessBuilder pb = new ProcessBuilder(backend);
    pb.environment().clear();
    pb.environment().putAll(env);
    Process cgi = pb.start();

    // Pipe the request body into the CGI, drain stderr so it cannot block.
    Thread stderr = new Thread(() -> drain(cgi.getErrorStream()));
    stderr.setDaemon(true);
    stderr.start();
    try (InputStream reqBody = exchange.getRequestBody();
        OutputStream cgiIn = cgi.getOutputStream()) {
      reqBody.transferTo(cgiIn);
    } catch (IOException ignored) {
      // best-effort — the CGI may have closed its stdin early
    }

    byte[] cgiOut = cgi.getInputStream().readAllBytes();
    try {
      cgi.waitFor(60, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      cgi.destroyForcibly();
    }

    // A CGI response is headers, a blank line, then the body. Split and replay.
    int split = indexOfBlankLine(cgiOut);
    int status = 200;
    byte[] bodyBytes;
    if (split >= 0) {
      String headerBlock = new String(cgiOut, 0, split, StandardCharsets.ISO_8859_1);
      for (String line : headerBlock.split("\r?\n")) {
        int c = line.indexOf(':');
        if (c < 0) {
          continue;
        }
        String name = line.substring(0, c).trim();
        String value = line.substring(c + 1).trim();
        if (name.equalsIgnoreCase("Status")) {
          status = Integer.parseInt(value.split("\\s+")[0]);
        } else {
          exchange.getResponseHeaders().add(name, value);
        }
      }
      int bodyStart = bodyStart(cgiOut, split);
      bodyBytes = new byte[cgiOut.length - bodyStart];
      System.arraycopy(cgiOut, bodyStart, bodyBytes, 0, bodyBytes.length);
    } else {
      bodyBytes = cgiOut;
    }
    exchange.sendResponseHeaders(status, bodyBytes.length == 0 ? -1 : bodyBytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bodyBytes);
    }
  }

  private static void drain(InputStream in) {
    try {
      in.readAllBytes();
    } catch (IOException ignored) {
      // best-effort
    }
  }

  /** Index of the first byte of the blank line separating CGI headers from the body. */
  private static int indexOfBlankLine(byte[] data) {
    for (int i = 0; i + 1 < data.length; i++) {
      if (data[i] == '\n' && data[i + 1] == '\n') {
        return i;
      }
      if (i + 3 < data.length
          && data[i] == '\r'
          && data[i + 1] == '\n'
          && data[i + 2] == '\r'
          && data[i + 3] == '\n') {
        return i;
      }
    }
    return -1;
  }

  /** First body byte after the blank line at {@code split}. */
  private static int bodyStart(byte[] data, int split) {
    if (split + 3 < data.length
        && data[split] == '\r'
        && data[split + 1] == '\n'
        && data[split + 2] == '\r'
        && data[split + 3] == '\n') {
      return split + 4;
    }
    return split + 2;
  }

  /** Locate the {@code git-http-backend} executable, or {@code null} when it cannot be found. */
  private static String httpBackend() {
    try {
      Process p = new ProcessBuilder("git", "--exec-path").redirectErrorStream(true).start();
      String execPath =
          new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || execPath.isEmpty()) {
        return null;
      }
      for (String candidate : List.of("git-http-backend", "git-http-backend.exe")) {
        Path backend = Path.of(execPath, candidate);
        if (Files.isRegularFile(backend)) {
          return backend.toAbsolutePath().toString();
        }
      }
      return null;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return null;
    }
  }
}
