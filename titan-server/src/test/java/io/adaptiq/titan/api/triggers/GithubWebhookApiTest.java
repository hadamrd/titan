package io.adaptiq.titan.api.triggers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import io.adaptiq.titan.job.Job;
import io.adaptiq.titan.job.JobService;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GithubWebhookApi} — exercises the HMAC verification + branch matching +
 * enqueue dispatch path (issue #397).
 *
 * <p>Strategy: plain JUnit (no Quarkus boot) — the receiver is instantiated directly with a real
 * H2-backed {@link TitanStores} (via {@link FakeTitanStores}), a tiny in-memory {@link JobService}
 * stub, and an in-memory {@link CredentialsService}. {@code HttpHeaders} is faked via a minimalist
 * record-style implementation. This isolates the HMAC + dispatch logic from Quarkus REST routing
 * entirely; the wire shape is covered by the IT.
 */
class GithubWebhookApiTest {

  private static final String SECRET = "test-webhook-secret";

  private TitanStores stores;
  private InMemoryJobService jobs;
  private InMemoryCredentials creds;
  private GithubWebhookApi api;

  @BeforeEach
  void setUp() throws Exception {
    // FakeTitanStores is package-private in io.adaptiq.titan.api — call via reflection so this
    // test sits in the api.triggers package without making the helper public surface area.
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);

    jobs = new InMemoryJobService();
    creds = new InMemoryCredentials();

    // Constructor-inject the SUT directly (package-private constructor).
    Class<?> apiClass = GithubWebhookApi.class;
    var ctor =
        apiClass.getDeclaredConstructor(
            TitanStores.class, JobService.class, CredentialsService.class);
    ctor.setAccessible(true);
    api = (GithubWebhookApi) ctor.newInstance(stores, jobs, creds);
  }

  // ── 1. valid signature + push to matching branch → enqueue ──────────────────

  @Test
  void validSignature_matchingBranch_pushEvent_enqueuesBuild() {
    long jobId = seedJob("kmajdoub/repo-a", "trunk");
    creds.put("github-webhook", "gh-secret", SECRET);

    byte[] body = pushBody("refs/heads/trunk");
    Response resp = api.receive(headers("sha256=" + hmac(SECRET, body), "push"), body);

    assertEquals(200, resp.getStatus());
    assertEquals(
        1, stores.builds().listByJob(jobId).size(), "exactly one build should be enqueued");
    assertTrue(
        stores.builds().listByJob(jobId).stream().anyMatch(b -> "github".equals(b.triggerType)),
        "build.triggerType must be 'github'");
  }

  // ── 2. invalid signature → 401, no enqueue ──────────────────────────────────

  @Test
  void invalidSignature_returns401_noEnqueue() {
    long jobId = seedJob("kmajdoub/repo-b", "trunk");
    creds.put("github-webhook", "gh-secret", SECRET);

    byte[] body = pushBody("refs/heads/trunk");
    // Sign with the WRONG secret.
    Response resp = api.receive(headers("sha256=" + hmac("wrong-secret", body), "push"), body);

    assertEquals(401, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty(), "no build should be enqueued on 401");
  }

  // ── 3. valid signature + non-matching branch → 200, no enqueue ──────────────

  @Test
  void validSignature_nonMatchingBranch_returns200_noEnqueue() {
    long jobId = seedJob("kmajdoub/repo-c", "trunk");
    creds.put("github-webhook", "gh-secret", SECRET);

    byte[] body = pushBody("refs/heads/develop");
    Response resp = api.receive(headers("sha256=" + hmac(SECRET, body), "push"), body);

    assertEquals(200, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 4. valid signature + unknown event → 200, no enqueue ────────────────────

  @Test
  void unknownEvent_returns200_noEnqueue() {
    long jobId = seedJob("kmajdoub/repo-d", "trunk");
    creds.put("github-webhook", "gh-secret", SECRET);

    byte[] body = pushBody("refs/heads/trunk");
    // Forward-compat event the SPI doesn't dispatch yet.
    Response resp = api.receive(headers("sha256=" + hmac(SECRET, body), "issue_opened"), body);

    assertEquals(200, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── 5. missing signature header → 401 ───────────────────────────────────────

  @Test
  void missingSignatureHeader_returns401() {
    long jobId = seedJob("kmajdoub/repo-e", "trunk");
    creds.put("github-webhook", "gh-secret", SECRET);

    byte[] body = pushBody("refs/heads/trunk");
    Response resp = api.receive(headers(null, "push"), body);

    assertEquals(401, resp.getStatus());
    assertTrue(stores.builds().listByJob(jobId).isEmpty());
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  /** Seed a job whose {@code config_json} carries a github trigger for the given branch. */
  private long seedJob(@NonNull String fullName, @NonNull String branch) {
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson =
        "{\"triggers\":[{\"type\":\"github\","
            + "\"id\":\"trig-1\","
            + "\"branches\":[\""
            + branch
            + "\"],"
            + "\"events\":[\"push\"],"
            + "\"credentialsId\":\"gh-secret\"}]}";
    long jobId = stores.jobs().insert(row);
    jobs.add(new Job(jobId, fullName, null, null, "", row.configJson, null, null, null, true));
    return jobId;
  }

  private static byte[] pushBody(@NonNull String ref) {
    return ("{\"ref\":\"" + ref + "\",\"after\":\"abc123\",\"repository\":{\"full_name\":\"x/y\"}}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @NonNull
  private static String hmac(@NonNull String secret, @NonNull byte[] body) {
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

  /** A minimal {@link HttpHeaders} stub — only the {@code getHeaderString} hot path is wired. */
  @NonNull
  private static HttpHeaders headers(String signature, String event) {
    Map<String, String> map = new HashMap<>();
    if (signature != null) {
      map.put("X-Hub-Signature-256", signature);
    }
    if (event != null) {
      map.put("X-GitHub-Event", event);
    }
    return new StubHeaders(map);
  }

  // ── #937: required-no-default param missing → skip, NOT enqueue a doomed build ─

  @Test
  void push_requiredParamWithoutDefault_skipsPipelineNotEnqueue() throws Exception {
    // Mirror of #931's GithubAppWebhookApiTest test on the legacy /api/v1/triggers/github path.
    // A pipeline whose discovered metadata declares a required-no-default parameter MUST be
    // skipped with a structured log instead of enqueueing a doomed FAILED build (issue #937).
    long jobMissing =
        seedJobWithDiscoveredMetadata(
            "kmajdoub/repo-missing",
            "trunk",
            "release.yml",
            "{\"triggers\":[{\"type\":\"push\"}],\"parameters\":["
                + "{\"name\":\"VERSION\",\"required\":true,\"hasDefault\":false}]}");
    long jobOk =
        seedJobWithDiscoveredMetadata(
            "kmajdoub/repo-ok",
            "trunk",
            "build.yml",
            "{\"triggers\":[{\"type\":\"push\"}],\"parameters\":["
                + "{\"name\":\"ENV\",\"required\":true,\"hasDefault\":true}]}");
    long jobNone =
        seedJobWithDiscoveredMetadata(
            "kmajdoub/repo-none", "trunk", "smoke.yml", "{\"triggers\":[{\"type\":\"push\"}]}");
    creds.put("github-webhook", "gh-secret", SECRET);

    byte[] body = pushBody("refs/heads/trunk");
    Response resp = api.receive(headers("sha256=" + hmac(SECRET, body), "push"), body);

    assertEquals(200, resp.getStatus());
    assertTrue(
        stores.builds().listByJob(jobMissing).isEmpty(),
        "required-no-default missing param: legacy webhook MUST skip the doomed build (#937)");
    assertEquals(
        1,
        stores.builds().listByJob(jobOk).size(),
        "sibling pipeline with required-with-default MUST still dispatch");
    assertEquals(
        1,
        stores.builds().listByJob(jobNone).size(),
        "sibling pipeline with no parameters MUST still dispatch");
  }

  /**
   * Seed a job linked to a discovered-pipeline row (design 66) carrying the given {@code
   * parsed_metadata} blob. The job's {@code config_json} carries a github trigger plus the filename
   * so {@link WebhookTriggerMatcher#extractFilename} can match it.
   */
  private long seedJobWithDiscoveredMetadata(
      @NonNull String fullName,
      @NonNull String branch,
      @NonNull String filename,
      @NonNull String metadata) {
    long installId = 4242L;
    long repoId = Math.abs(fullName.hashCode()) + 1000L;
    int slash = fullName.indexOf('/');
    String owner = slash > 0 ? fullName.substring(0, slash) : fullName;
    String name = slash > 0 ? fullName.substring(slash + 1) : fullName;
    if (stores.githubInstallations().findByInstallId(installId).isEmpty()) {
      stores.githubInstallations().insert(installId, owner, "User", "User", null);
    }
    stores.githubRepositories().deleteByRepoId(repoId);
    stores.githubRepositories().insert(installId, repoId, owner, name, branch, false);
    stores
        .githubPipelinesDiscovered()
        .insert(repoId, branch, filename, "sha-" + filename, metadata, null);
    JobRow row = new JobRow();
    row.fullName = fullName;
    row.enabled = true;
    row.pipelineScript = "";
    row.configJson =
        "{\"filename\":\""
            + filename
            + "\",\"triggers\":[{\"type\":\"github\","
            + "\"id\":\"trig-1\","
            + "\"branches\":[\""
            + branch
            + "\"],"
            + "\"events\":[\"push\"],"
            + "\"credentialsId\":\"gh-secret\"}]}";
    long jobId = stores.jobs().insert(row);
    stores.withTransaction(
        conn -> {
          try (var st =
              conn.prepareStatement(
                  "UPDATE titan.jobs SET github_installation_id = ?, github_repo_id = ? WHERE id = ?")) {
            st.setLong(1, installId);
            st.setLong(2, repoId);
            st.setLong(3, jobId);
            st.executeUpdate();
            return null;
          } catch (java.sql.SQLException e) {
            throw new RuntimeException(e);
          }
        });
    jobs.add(new Job(jobId, fullName, null, null, "", row.configJson, null, null, null, true));
    return jobId;
  }

  // ── branch matcher smoke ────────────────────────────────────────────────────

  @Test
  void branchMatcher_emptyPatterns_matchesAny() {
    assertTrue(GithubWebhookApi.branchMatches(List.of(), "anything"));
  }

  @Test
  void branchMatcher_exactMatch() {
    assertTrue(GithubWebhookApi.branchMatches(List.of("trunk"), "trunk"));
    assertFalse(GithubWebhookApi.branchMatches(List.of("trunk"), "develop"));
  }

  @Test
  void branchMatcher_doubleStarPrefix() {
    assertTrue(GithubWebhookApi.branchMatches(List.of("feat/**"), "feat/x"));
    assertTrue(GithubWebhookApi.branchMatches(List.of("feat/**"), "feat/nested/x"));
    assertFalse(GithubWebhookApi.branchMatches(List.of("feat/**"), "trunk"));
  }

  // ── direct HMAC verifier smoke ──────────────────────────────────────────────

  @Test
  void verifyHmac_rejectsMissingSha256Prefix() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertFalse(GithubWebhookApi.verifyHmac(SECRET, body, hmac(SECRET, body)));
  }

  @Test
  void verifyHmac_acceptsCorrectSignature() {
    byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
    assertTrue(GithubWebhookApi.verifyHmac(SECRET, body, "sha256=" + hmac(SECRET, body)));
  }

  // ── stubs ───────────────────────────────────────────────────────────────────

  /** In-memory {@link JobService} backed by an {@link java.util.ArrayList}. */
  static final class InMemoryJobService implements JobService {
    private final List<Job> all = new java.util.ArrayList<>();

    void add(@NonNull Job job) {
      all.add(job);
    }

    @Override
    @NonNull
    public Optional<Job> findById(long id) {
      return all.stream().filter(j -> j.id() == id).findFirst();
    }

    @Override
    @NonNull
    public Optional<Job> findByFullName(@NonNull String fullName) {
      return all.stream().filter(j -> fullName.equals(j.fullName())).findFirst();
    }

    @Override
    @NonNull
    public List<Job> listAll() {
      return List.copyOf(all);
    }

    @Override
    @NonNull
    public List<io.adaptiq.titan.job.JobWithLastBuild> listAllWithLastBuild() {
      return all.stream().map(j -> new io.adaptiq.titan.job.JobWithLastBuild(j, null)).toList();
    }

    @Override
    public void delete(long id) {
      all.removeIf(j -> j.id() == id);
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
  }

  /** In-memory {@link CredentialsService} — only {@code resolvePlaintext} is wired. */
  static final class InMemoryCredentials implements CredentialsService {
    private final Map<String, String> store = new HashMap<>();

    void put(@NonNull String scope, @NonNull String key, @NonNull String value) {
      store.put(scope + "/" + key, value);
    }

    @Override
    @NonNull
    public Optional<String> resolvePlaintext(@NonNull String scope, @NonNull String key) {
      return Optional.ofNullable(store.get(scope + "/" + key));
    }

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
    @NonNull
    public String backendName() {
      return "in-memory-test";
    }

    @Override
    public int rotateKek() {
      return 0;
    }
  }

  /** Minimal {@link HttpHeaders} — only the hot accessors used by {@link GithubWebhookApi}. */
  static final class StubHeaders implements HttpHeaders {
    private final Map<String, String> headers;

    StubHeaders(@NonNull Map<String, String> headers) {
      this.headers = headers;
    }

    @Override
    public String getHeaderString(String name) {
      // Case-insensitive lookup.
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
      headers.forEach((k, v) -> out.add(k, v));
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
