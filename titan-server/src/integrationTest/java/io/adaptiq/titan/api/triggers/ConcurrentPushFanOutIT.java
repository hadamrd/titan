package io.adaptiq.titan.api.triggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * design/69 — concurrent-push fan-out policy. Locks in the v1 contract: every HMAC-verified,
 * branch-matching webhook delivery enqueues a fresh {@code QUEUED} build row. No dedupe, no
 * debounce, no rate-limit. Three webhooks fired within 100 ms for the same branch — even with
 * identical bodies (same head SHA, same actor) — MUST produce three distinct builds.
 *
 * <p>If a future "helpful" PR silently adds debounce or dedupe at the receiver, this test fails and
 * the PR is blocked. Companion to {@code docs/design/69-concurrent-push-policy.md}.
 */
@Testcontainers
class ConcurrentPushFanOutIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String SECRET_PLAINTEXT = "test-webhook-secret";

  private HikariDataSource ds;
  private TitanStores stores;
  private CredentialsService credentials;
  private GithubWebhookApi api;
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
            Credential.KIND_STRING, "github-webhook", "gh-secret", SECRET_PLAINTEXT));

    JobRow row = new JobRow();
    row.fullName = "kmajdoub/fan-out-it-" + System.nanoTime();
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson =
        "{\"triggers\":[{\"type\":\"github\","
            + "\"id\":\"trig-1\","
            + "\"branches\":[\"trunk\"],"
            + "\"events\":[\"push\"],"
            + "\"credentialsId\":\"gh-secret\"}]}";
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
    api = ctor.newInstance(stores, new StaticJobService(job), credentials);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void threeWebhooksSameBranchSameSha_within100ms_produceThreeBuilds() throws Exception {
    // Identical payload — same ref, same head_commit.id, same actor. This is the strongest
    // possible "looks like a duplicate" signal a debounce/dedupe heuristic could latch onto.
    byte[] body =
        ("{\"ref\":\"refs/heads/trunk\","
                + "\"after\":\"deadbeef1234567\","
                + "\"head_commit\":{\"id\":\"a3f9c12abcdef0123456789\"},"
                + "\"pusher\":{\"name\":\"kira.rai\"},"
                + "\"repository\":{\"full_name\":\"kmajdoub/repo\"}}")
            .getBytes(StandardCharsets.UTF_8);
    String signature = "sha256=" + hmacHex(SECRET_PLAINTEXT, body);

    long t0 = System.nanoTime();
    Response r1 = api.receive(headers(signature, "push"), body);
    Response r2 = api.receive(headers(signature, "push"), body);
    Response r3 = api.receive(headers(signature, "push"), body);
    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

    assertEquals(200, r1.getStatus(), "delivery 1 should accept");
    assertEquals(200, r2.getStatus(), "delivery 2 should accept");
    assertEquals(200, r3.getStatus(), "delivery 3 should accept");

    List<BuildRow> builds = stores.builds().listByJob(jobId);
    assertEquals(
        3,
        builds.size(),
        "design/69 — three webhooks for the same branch+SHA MUST produce three distinct builds "
            + "(no dedupe / no debounce / no rate-limit at the receiver). Found: "
            + builds.size());

    Set<Long> ids = new HashSet<>();
    Set<Integer> numbers = new HashSet<>();
    for (BuildRow b : builds) {
      ids.add(b.id);
      numbers.add(b.buildNumber);
      assertEquals(
          "QUEUED",
          b.status,
          "every fan-out build must start in QUEUED — worker pool is the only backpressure");
      assertEquals("github", b.triggerType);
    }
    assertEquals(3, ids.size(), "each build must have a distinct id");
    assertEquals(3, numbers.size(), "each build must have a distinct build_number");

    // Sanity: assertion is meaningful only if the three calls truly were close together.
    // If this ever exceeds a couple of seconds the test environment is the suspect, not
    // the contract; but the documented contract is "within 100 ms" so we log if we drift.
    assertTrue(elapsedMs < 5_000, "three receives took " + elapsedMs + " ms — too slow");
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

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
    public List<MediaType> getAcceptableMediaTypes() {
      return List.of();
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
      return List.of();
    }

    @Override
    public MediaType getMediaType() {
      return null;
    }

    @Override
    public Locale getLanguage() {
      return null;
    }

    @Override
    public Map<String, Cookie> getCookies() {
      return Map.of();
    }

    @Override
    public Date getDate() {
      return null;
    }

    @Override
    public int getLength() {
      return -1;
    }
  }
}
