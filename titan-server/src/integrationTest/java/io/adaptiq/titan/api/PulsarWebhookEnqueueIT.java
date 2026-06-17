package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.pulsar.PulsarEventSource;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for the {@link PulsarWebhookApi} enqueue path (issue #1287).
 *
 * <p><strong>Fixture (recorded, no network, no container).</strong> Builds a real on-disk git
 * repository with a {@code .titan/pipelines/ci.yml}, publishes the tip under {@code
 * refs/pulsar/changes/<id>} into a bare mirror that stands in for the Pulsar node's git smart-HTTP
 * endpoint, and points a REAL {@link PulsarEventSource} at it via a {@code file://} URL — so the
 * webhook's actual clone + {@code .titan/pipelines} discovery leg is exercised end-to-end. A real
 * H2-backed {@link TitanStores} ({@code FakeTitanStores}) gives a real DAO transaction, so the
 * enqueued build is asserted via the build store exactly like the GitHub webhook IT.
 *
 * <p>Acceptance under test: a signed change-event whose tip carries a pipeline → a {@code QUEUED}
 * build appears for the repo's job (the real trigger path), and a redelivery does not double-build.
 */
class PulsarWebhookEnqueueIT {

  private static final String REPO = "acme/web";
  private static final String SECRET = "it-pulsar-secret";

  @TempDir Path root;

  private PulsarWebhookApi api;
  private TitanStores stores;
  private long jobId;
  private String pipelineRevision;

  @BeforeEach
  void buildFixture() throws Exception {
    Path nodes = root.resolve("nodes");
    Path work = root.resolve("work");
    Files.createDirectories(work);

    git(work, "init", "-q", "-b", "main", ".");
    write(work, ".titan/pipelines/ci.yml", "stages:\n  - name: build\n");
    git(work, "add", "-A");
    git(work, "-c", "user.email=t@titan", "-c", "user.name=titan", "commit", "-q", "-m", "ci");
    pipelineRevision = git(work, "rev-parse", "HEAD").trim();

    git(work, "update-ref", "refs/pulsar/changes/42", pipelineRevision);
    Path mirror = nodes.resolve(REPO + ".git");
    Files.createDirectories(mirror.getParent());
    git(root, "clone", "-q", "--mirror", work.toUri().toString(), mirror.toString());

    PulsarEventSource source = new PulsarEventSource(nodes.toUri().toString());
    stores = FakeTitanStores.create();
    api = new PulsarWebhookApi(stores, () -> Optional.of(SECRET), source::triggerFor);

    JobRow row = new JobRow();
    row.fullName = REPO;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson = "{}";
    jobId = stores.jobs().insert(row);
  }

  @Test
  void signedChangeEvent_realCloneDiscovers_enqueuesBuild() {
    byte[] body = changeBody(REPO, "42", pipelineRevision);

    Response resp = api.receive(headers(sig(SECRET, body), "delivery-it-1"), body);

    assertEquals(202, resp.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());
    assertEquals("pulsar", stores.builds().listByJob(jobId).get(0).triggerType);
  }

  @Test
  void redelivery_sameDeliveryId_doesNotDoubleBuild() {
    byte[] body = changeBody(REPO, "42", pipelineRevision);
    String signature = sig(SECRET, body);

    Response first = api.receive(headers(signature, "delivery-it-2"), body);
    Response second = api.receive(headers(signature, "delivery-it-2"), body);

    assertEquals(202, first.getStatus());
    assertEquals(204, second.getStatus());
    assertEquals(1, stores.builds().listByJob(jobId).size());
  }

  @Test
  void badSignature_returns401_noBuild() {
    byte[] body = changeBody(REPO, "42", pipelineRevision);

    Response resp = api.receive(headers(sig("wrong", body), "delivery-it-3"), body);

    assertEquals(401, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── git fixture + http helpers ──────────────────────────────────────────────

  private static String git(Path cwd, String... args) throws Exception {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!p.waitFor(60, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new IllegalStateException("git " + args[0] + " timed out");
    }
    if (p.exitValue() != 0) {
      throw new IllegalStateException("git " + args[0] + " exit=" + p.exitValue() + ": " + out);
    }
    return out;
  }

  private static void write(Path repo, String relPath, String content) {
    try {
      Path target = repo.resolve(relPath);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @NonNull
  private static byte[] changeBody(
      @NonNull String repo, @NonNull String changeId, @NonNull String revision) {
    return ("{\"repo\":\""
            + repo
            + "\",\"changeId\":\""
            + changeId
            + "\",\"revision\":\""
            + revision
            + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @NonNull
  private static String sig(@NonNull String secret, @NonNull byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return "sha256=" + sb;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @NonNull
  private static HttpHeaders headers(String signature, String deliveryId) {
    Map<String, String> map = new HashMap<>();
    if (signature != null) {
      map.put("X-Pulsar-Signature-256", signature);
    }
    if (deliveryId != null) {
      map.put("X-Pulsar-Delivery", deliveryId);
    }
    return new StubHeaders(map);
  }

  static final class StubHeaders implements HttpHeaders {
    private final Map<String, String> headers;

    StubHeaders(@NonNull Map<String, String> headers) {
      this.headers = headers;
    }

    @Override
    public String getHeaderString(String name) {
      for (var e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue();
        }
      }
      return null;
    }

    @Override
    public List<String> getRequestHeader(String name) {
      String v = getHeaderString(name);
      return v == null ? List.of() : List.of(v);
    }

    @Override
    public MultivaluedMap<String, String> getRequestHeaders() {
      MultivaluedMap<String, String> out = new MultivaluedHashMap<>();
      headers.forEach(out::add);
      return out;
    }

    @Override
    public List<jakarta.ws.rs.core.MediaType> getAcceptableMediaTypes() {
      return List.of();
    }

    @Override
    public List<java.util.Locale> getAcceptableLanguages() {
      return List.of();
    }

    @Override
    public jakarta.ws.rs.core.MediaType getMediaType() {
      return null;
    }

    @Override
    public java.util.Locale getLanguage() {
      return null;
    }

    @Override
    public Map<String, jakarta.ws.rs.core.Cookie> getCookies() {
      return Map.of();
    }

    @Override
    public java.util.Date getDate() {
      return null;
    }

    @Override
    public int getLength() {
      return -1;
    }
  }
}
