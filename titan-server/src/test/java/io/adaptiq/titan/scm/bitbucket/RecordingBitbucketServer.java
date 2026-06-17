package io.adaptiq.titan.scm.bitbucket;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * In-process recording HTTP server for the Bitbucket adapter tests — same approach the existing
 * {@code BitbucketStatusReporterTest} / {@code TitanNotifyIT} use (JDK {@code HttpServer}, no extra
 * test dependency). A pluggable {@link Responder} lets each test script status codes / bodies /
 * headers per request (e.g. first call 429 then 201).
 */
final class RecordingBitbucketServer implements AutoCloseable {

  /** One captured request. */
  record Recorded(
      @NonNull String method,
      @NonNull String path,
      @NonNull String body,
      @NonNull Map<String, String> headers) {}

  /** Programmable response for one request — chosen by the test via request index/path. */
  record Reply(int status, @NonNull String body, @NonNull Map<String, String> headers) {
    static Reply ok(@NonNull String body) {
      return new Reply(200, body, Map.of());
    }

    static Reply created(@NonNull String body) {
      return new Reply(201, body, Map.of());
    }

    static Reply status(int code) {
      return new Reply(code, "{}", Map.of());
    }

    static Reply statusWithHeader(int code, @NonNull String name, @NonNull String value) {
      return new Reply(code, "{}", Map.of(name, value));
    }
  }

  /** Maps a recorded request (already captured) to the reply to send. */
  @FunctionalInterface
  interface Responder extends Function<Recorded, Reply> {}

  private final HttpServer server;
  private final List<Recorded> recorded = new ArrayList<>();
  private final Responder responder;

  RecordingBitbucketServer(@NonNull Responder responder) throws IOException {
    this.responder = responder;
    this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", this::handle);
    server.start();
  }

  private void handle(@NonNull HttpExchange ex) throws IOException {
    byte[] bodyBytes = ex.getRequestBody().readAllBytes();
    Map<String, String> headers = new HashMap<>();
    ex.getRequestHeaders().forEach((k, v) -> headers.put(k, String.join(",", v)));
    Recorded rec =
        new Recorded(
            ex.getRequestMethod(),
            ex.getRequestURI().toString(),
            new String(bodyBytes, StandardCharsets.UTF_8),
            headers);
    Reply reply;
    synchronized (recorded) {
      recorded.add(rec);
      reply = responder.apply(rec);
    }
    byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
    reply.headers().forEach((k, val) -> ex.getResponseHeaders().add(k, val));
    ex.sendResponseHeaders(reply.status(), out.length);
    ex.getResponseBody().write(out);
    ex.close();
  }

  @NonNull
  String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @NonNull
  List<Recorded> recorded() {
    synchronized (recorded) {
      return new ArrayList<>(recorded);
    }
  }

  @Nullable
  Recorded firstMatching(@NonNull String method, @NonNull String pathPrefix) {
    synchronized (recorded) {
      for (Recorded r : recorded) {
        if (r.method().equals(method) && r.path().startsWith(pathPrefix)) {
          return r;
        }
      }
    }
    return null;
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
