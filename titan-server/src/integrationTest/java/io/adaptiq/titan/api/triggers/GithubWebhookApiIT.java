package io.adaptiq.titan.api.triggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Constructor;
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
 * Testcontainers integration test for {@link GithubWebhookApi} — proves the receiver round-trips
 * through real Postgres + the real credentials envelope-cipher stack (issue #397).
 *
 * <p>Setup:
 *
 * <ul>
 *   <li>Postgres 16 in a Testcontainer, fresh schema, Flyway migrations applied.
 *   <li>Real {@link CredentialsService} backed by {@link DbEnvelopeBackend} with a fixed test KEK —
 *       so a seeded plaintext secret round-trips through seal+unseal.
 *   <li>One seeded job with a github trigger in its {@code config_json}, branches=[{@code trunk}],
 *       credentialsId={@code gh-secret}.
 *   <li>The credential's value is {@code test-webhook-secret}; the body is HMAC-signed with that.
 * </ul>
 *
 * <p>Assertion: a build row appears for the seeded job after {@link GithubWebhookApi#receive}.
 */
@Testcontainers
class GithubWebhookApiIT {

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

    // Real credentials stack with a fixed KEK — so we can seal a known plaintext and the receiver
    // can unseal it through the canonical resolvePlaintext path.
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

    // Seed the secret.
    credentials.create(
        new NewCredentialRequest(
            Credential.KIND_STRING, "github-webhook", "gh-secret", SECRET_PLAINTEXT));

    // Seed the job with a github trigger on `trunk`.
    JobRow row = new JobRow();
    row.fullName = "kmajdoub/wh-it-" + System.nanoTime();
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

    // Wire the receiver via its package-private constructor (no Quarkus REST routing — we are
    // exercising the dispatch path against a real store, not the HTTP boundary).
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
  void githubPushOnMatchingBranch_signed_enqueuesBuild() {
    byte[] body =
        ("{\"ref\":\"refs/heads/trunk\","
                + "\"after\":\"deadbeef1234567\","
                + "\"head_commit\":{\"id\":\"a3f9c12abcdef0123456789\"},"
                + "\"pusher\":{\"name\":\"kira.rai\"},"
                + "\"repository\":{\"full_name\":\"kmajdoub/repo\"}}")
            .getBytes(StandardCharsets.UTF_8);
    String signature = "sha256=" + hmacHex(SECRET_PLAINTEXT, body);

    Response resp = api.receive(headers(signature, "push"), body);

    assertEquals(200, resp.getStatus(), "valid signature + matching branch must return 200");
    assertFalse(
        stores.builds().listByJob(jobId).isEmpty(),
        "a build row must exist for the seeded job after dispatch");
    assertTrue(
        stores.builds().listByJob(jobId).stream().anyMatch(b -> "github".equals(b.triggerType)),
        "the build's triggerType must be 'github'");

    // Issue #589 — webhook-born build must carry structured trigger metadata.
    var build =
        stores.builds().listByJob(jobId).stream()
            .filter(b -> "github".equals(b.triggerType))
            .findFirst()
            .orElseThrow();
    assertTrue(
        build.triggerMetaJson != null && !build.triggerMetaJson.isBlank(),
        "trigger_meta_json must be populated for a webhook-born build");
    assertTrue(
        build.triggerMetaJson.contains("\"branch\":\"trunk\""),
        "trigger_meta_json must carry the resolved branch — was: " + build.triggerMetaJson);
    assertTrue(
        build.triggerMetaJson.contains("\"commitSha\":\"a3f9c12\""),
        "trigger_meta_json must carry the 7-char short SHA — was: " + build.triggerMetaJson);
    assertTrue(
        build.triggerMetaJson.contains("\"actor\":\"kira.rai\""),
        "trigger_meta_json must carry the pusher name — was: " + build.triggerMetaJson);
    // Secrets-policy backstop: only the three stripped fields are persisted, never repo objects.
    assertFalse(
        build.triggerMetaJson.contains("repository"),
        "trigger_meta_json must NOT copy nested webhook objects (secrets-policy)");
  }

  @Test
  void githubPush_enqueuesSynthesizeNotBake_closes821() {
    // Regression #821: webhook-discovered builds must be driven through SYNTHESIZE first,
    // not BAKE — otherwise QueueProcessor.handleBake fails with "build N has no synthesized
    // model — synthesize must run first" and the build never runs.
    byte[] body =
        ("{\"ref\":\"refs/heads/trunk\","
                + "\"after\":\"feedface1234567\","
                + "\"head_commit\":{\"id\":\"feedfacedeadbeef\"},"
                + "\"pusher\":{\"name\":\"kira.rai\"},"
                + "\"repository\":{\"full_name\":\"kmajdoub/repo\"}}")
            .getBytes(StandardCharsets.UTF_8);
    String signature = "sha256=" + hmacHex(SECRET_PLAINTEXT, body);

    Response resp = api.receive(headers(signature, "push"), body);
    assertEquals(200, resp.getStatus());

    var build =
        stores.builds().listByJob(jobId).stream()
            .filter(b -> "github".equals(b.triggerType))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no webhook-born build was enqueued"));

    // The orchestrate task for this build MUST be SYNTHESIZE, not BAKE.
    List<io.adaptiq.titan.store.rows.TaskQueueRow> tasks = stores.taskQueue().listByBuild(build.id);
    assertFalse(tasks.isEmpty(), "an ORCHESTRATE task must be enqueued for the webhook build");
    var orch =
        tasks.stream()
            .filter(t -> "ORCHESTRATE".equals(t.type))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no ORCHESTRATE task found for the build"));
    assertTrue(
        orch.payloadJson != null && orch.payloadJson.contains("\"action\":\"SYNTHESIZE\""),
        "webhook-born ORCHESTRATE task must carry action=SYNTHESIZE (closes #821) — was: "
            + orch.payloadJson);
    assertFalse(
        orch.payloadJson.contains("\"action\":\"BAKE\""),
        "webhook-born ORCHESTRATE task MUST NOT enqueue BAKE directly (closes #821) — was: "
            + orch.payloadJson);
  }

  @Test
  void manualBuildHasNullTriggerMeta() {
    // Insert a build the way JobBuildsApi.triggerBuild does — no webhook, no metadata.
    io.adaptiq.titan.store.rows.BuildRow row = new io.adaptiq.titan.store.rows.BuildRow();
    row.jobId = jobId;
    row.buildNumber = 1;
    row.status = "QUEUED";
    row.triggeredBy = "alice";
    row.triggerType = "manual";
    row.queuedAt = java.time.Instant.now();
    long manualId = stores.builds().insert(row);

    var manual = stores.builds().findById(manualId).orElseThrow();
    org.junit.jupiter.api.Assertions.assertNull(
        manual.triggerMetaJson,
        "a manually-triggered build must NOT carry trigger metadata (it has none)");
  }

  @Test
  void githubPush_invalidSignature_doesNotEnqueue() {
    byte[] body =
        ("{\"ref\":\"refs/heads/trunk\","
                + "\"after\":\"x\","
                + "\"repository\":{\"full_name\":\"kmajdoub/repo\"}}")
            .getBytes(StandardCharsets.UTF_8);
    // Sign with the wrong secret.
    String badSig = "sha256=" + hmacHex("wrong-secret", body);

    Response resp = api.receive(headers(badSig, "push"), body);

    assertEquals(401, resp.getStatus());
    assertTrue(
        stores.builds().listByJob(jobId).isEmpty(),
        "no build should be enqueued for an invalid signature");
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

  /** A minimal {@link JobService} that returns a single fixed job. */
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
    public List<io.adaptiq.titan.job.JobWithLastBuild> listAllWithLastBuild() {
      return List.of(new io.adaptiq.titan.job.JobWithLastBuild(job, null));
    }

    @Override
    @NonNull
    public Job create(@NonNull io.adaptiq.titan.job.NewJobRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    @NonNull
    public Job update(long id, @NonNull io.adaptiq.titan.job.JobUpdate update) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void delete(long id) {
      throw new UnsupportedOperationException();
    }
  }

  /** Minimal {@link HttpHeaders} for the IT — only the accessors used by the receiver. */
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
