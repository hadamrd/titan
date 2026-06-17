package io.adaptiq.titan.api.triggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.CredentialsServiceImpl;
import io.adaptiq.titan.credentials.DbEnvelopeBackend;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.job.JobUpdate;
import io.adaptiq.titan.job.JobWithLastBuild;
import io.adaptiq.titan.job.NewJobRequest;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import io.adaptiq.titan.webhook.WebhooksApi;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Testcontainers-backed integration test for the {@code /api/v1/webhooks/github} → {@code
 * /api/v1/triggers/github} deprecation contract (issue #970, design/52).
 *
 * <p>Two halves:
 *
 * <ol>
 *   <li><strong>Shim half</strong> — call {@link WebhooksApi#receive()} directly and assert the
 *       response status is 308, the {@code Location} header points at the canonical path, the
 *       {@code Deprecation}/{@code Sunset}/{@code Link} headers carry the RFC 8594 deprecation
 *       signal, and the body is empty. The shim has no DI dependencies — no dispatcher, no
 *       credentials service, no stores — so this also locks in the "pure redirect, no side effects"
 *       contract.
 *   <li><strong>Follow-the-redirect half</strong> — pre-seed a job + a github trigger + a
 *       credential against a real Postgres, then post a canonical, HMAC-signed payload to the
 *       canonical endpoint (the same one the legacy shim now redirects to) and assert exactly one
 *       build row appears with a non-null {@code triggerMetaJson.sha} matching the payload. This is
 *       the integration-level proof that the dedupe doesn't drop the trigger-metadata guarantee
 *       (#26/#27/#40) on the path GitHub will hit after following the 308.
 * </ol>
 *
 * <p>We deliberately do not stand up a full HTTP server here — that adds Quarkus boot time and
 * Keycloak weight for a contract already covered by the unit-level {@code @QuarkusTest}. The point
 * of the IT is "real DB, real cipher, real handler, real signature check" — the HTTP surface is one
 * method call away, and the e2e Playwright spec exercises the wire format.
 */
@Testcontainers
class GithubWebhookRedirectIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SECRET_PLAINTEXT = "redirect-it-secret";

  private HikariDataSource ds;
  private TitanStores stores;
  private CredentialsService credentials;
  private GithubWebhookApi canonical;
  private long jobId;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(8);
    ds = new HikariDataSource(cfg);

    try (Connection c = ds.getConnection();
        Statement st = c.createStatement()) {
      st.execute("DROP SCHEMA IF EXISTS titan CASCADE");
      st.execute("CREATE SCHEMA titan");
    }
    Flyway.configure(getClass().getClassLoader())
        .dataSource(ds)
        .schemas("titan")
        .defaultSchema("titan")
        .locations(
            "classpath:io/adaptiq/titan/db/migration",
            "classpath:io/adaptiq/titan/db/migration-postgresql")
        .load()
        .migrate();
    stores = TitanStores.forDataSource(ds);

    byte[] kek = new byte[32];
    CredentialKeyProvider keyProvider =
        new CredentialKeyProvider() {
          @Override
          public byte[] credentialKey() {
            return kek.clone();
          }

          @Override
          public String describe() {
            return "test:fixed";
          }
        };
    credentials = new CredentialsServiceImpl(new DbEnvelopeBackend(stores, keyProvider));

    credentials.create(
        new NewCredentialRequest(
            Credential.KIND_STRING, "github-webhook", "redirect-it-sec", SECRET_PLAINTEXT));

    JobRow row = new JobRow();
    row.fullName = "adaptiq/redirect-it-" + System.nanoTime();
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson =
        "{\"triggers\":[{\"type\":\"github\","
            + "\"id\":\"trig-1\","
            + "\"branches\":[\"trunk\"],"
            + "\"events\":[\"push\"],"
            + "\"credentialsId\":\"redirect-it-sec\"}]}";
    jobId = stores.jobs().insert(row);
    Job job =
        new Job(
            jobId,
            row.fullName,
            null,
            null,
            row.pipelineScript,
            row.configJson,
            null,
            null,
            null,
            true);

    Constructor<GithubWebhookApi> ctor =
        GithubWebhookApi.class.getDeclaredConstructor(
            TitanStores.class, JobService.class, CredentialsService.class);
    ctor.setAccessible(true);
    canonical = ctor.newInstance(stores, new StaticJobService(job), credentials);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── shim half: 308 + headers + empty body, no DI required ──────────────────

  @Test
  void legacyShim_returns308WithDeprecationHeaders() throws Exception {
    WebhooksApi shim = new WebhooksApi();

    // Call the route method directly — no HTTP server, no DI graph. The shim is intentionally
    // free of dependencies; if anybody puts a @ConfigProperty or constructor-injected service
    // back into it, this test fails to compile.
    Method receive = WebhooksApi.class.getDeclaredMethod("receive");
    Response resp = (Response) receive.invoke(shim);

    assertEquals(308, resp.getStatus(), "legacy path must permanent-redirect");
    assertEquals("/api/v1/triggers/github", resp.getHeaderString("Location"));
    assertEquals("true", resp.getHeaderString("Deprecation"));
    assertNotNull(resp.getHeaderString("Sunset"), "Sunset header must be present (RFC 8594)");
    assertEquals(
        "</api/v1/triggers/github>; rel=\"successor-version\"", resp.getHeaderString("Link"));
    assertTrue(
        resp.getEntity() == null,
        "308 shim must not emit a body — got entity: " + resp.getEntity());
  }

  // ── follow-the-redirect half: canonical endpoint creates the build row ─────

  @Test
  void postToCanonicalAfterRedirect_createsBuildWithTriggerMeta() {
    String fullSha = "cafebabe1234567890abcdef0123456789abcdef";
    byte[] body =
        ("{\"ref\":\"refs/heads/trunk\","
                + "\"after\":\""
                + fullSha
                + "\","
                + "\"head_commit\":{\"id\":\""
                + fullSha
                + "\"},"
                + "\"pusher\":{\"name\":\"redirect-bot\"},"
                + "\"repository\":{\"full_name\":\"adaptiq/repo\"}}")
            .getBytes(StandardCharsets.UTF_8);
    String signature = "sha256=" + hmacHex(SECRET_PLAINTEXT, body);

    Response resp = canonical.receive(headers(signature, "push"), body);
    assertEquals(200, resp.getStatus(), "canonical endpoint must accept the redirected POST");

    var builds = stores.builds().listByJob(jobId);
    assertFalse(builds.isEmpty(), "exactly one build must be created via the canonical path");
    assertEquals(1, builds.size(), "must be exactly one build — got " + builds.size());
    var build = builds.get(0);
    assertEquals(
        "github", build.triggerType, "build must be stamped triggerType=github (not 'webhook')");
    assertNotNull(
        build.triggerMetaJson,
        "triggerMetaJson MUST be populated — the whole point of #970 is to never land "
            + "a webhook-born build with NULL trigger metadata");
    assertTrue(
        build.triggerMetaJson.contains("\"branch\":\"trunk\""),
        "triggerMetaJson must carry branch — was: " + build.triggerMetaJson);
    assertTrue(
        build.triggerMetaJson.contains("\"commitSha\":\"" + fullSha + "\""),
        "triggerMetaJson must carry the full commit SHA — was: " + build.triggerMetaJson);
  }

  // ── adversarial: a body that would have been "accepted" by the legacy receiver
  //    (no signature, no matching repo) must NOT silently produce a build via the
  //    canonical path either. Guarantees the dedupe doesn't soften authentication.

  @Test
  void postToCanonicalAfterRedirect_unsignedBody_doesNotCreateBuild() {
    byte[] body =
        ("{\"ref\":\"refs/heads/trunk\","
                + "\"after\":\"deadbeef\","
                + "\"repository\":{\"full_name\":\"adaptiq/repo\"}}")
            .getBytes(StandardCharsets.UTF_8);
    // Sign with the wrong secret — simulates an attacker who knew the legacy URL but not the
    // per-trigger credential.
    String badSig = "sha256=" + hmacHex("wrong-secret", body);

    Response resp = canonical.receive(headers(badSig, "push"), body);

    assertEquals(401, resp.getStatus(), "canonical endpoint must reject bad signature");
    assertTrue(
        stores.builds().listByJob(jobId).isEmpty(),
        "no build may be enqueued when the redirected POST has no valid signature");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  @NonNull
  private static String hmacHex(@NonNull String secret, @NonNull byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] digest = mac.doFinal(body);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @NonNull
  private static HttpHeaders headers(@NonNull String signature, @NonNull String event) {
    Map<String, String> m = new HashMap<>();
    m.put("X-Hub-Signature-256", signature);
    m.put("X-GitHub-Event", event);
    return new StubHeaders(m);
  }

  private static final class StaticJobService implements JobService {
    private final Job job;

    StaticJobService(@NonNull Job job) {
      this.job = job;
    }

    @Override
    @NonNull
    public Optional<Job> findById(long id) {
      return id == job.id() ? Optional.of(job) : Optional.empty();
    }

    @Override
    @NonNull
    public Optional<Job> findByFullName(@NonNull String fullName) {
      return job.fullName().equals(fullName) ? Optional.of(job) : Optional.empty();
    }

    @Override
    @NonNull
    public List<Job> listAll() {
      return List.of(job);
    }

    @Override
    @NonNull
    public List<JobWithLastBuild> listAllWithLastBuild() {
      return List.of(new JobWithLastBuild(job, null));
    }

    @Override
    @NonNull
    public Job create(@NonNull NewJobRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Job update(long id, @NonNull JobUpdate update) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(long id) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class StubHeaders implements HttpHeaders {
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
    public List<Locale> getAcceptableLanguages() {
      return List.of();
    }

    @Override
    public jakarta.ws.rs.core.MediaType getMediaType() {
      return null;
    }

    @Override
    public Locale getLanguage() {
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
