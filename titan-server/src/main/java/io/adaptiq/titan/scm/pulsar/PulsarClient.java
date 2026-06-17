package io.adaptiq.titan.scm.pulsar;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonProcessingException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed HTTP client for a single Pulsar node's {@code /_pulsar/*} API (issue #1280, convergence
 * axis 2 / scm-depth). Mirrors {@link io.adaptiq.titan.scm.github.GithubClientFactory}'s role: a
 * thin {@code java.net.http} boundary with bounded timeouts that returns typed results and raises
 * {@link PulsarApiException} on any non-2xx status or malformed body.
 *
 * <p>Three reads, all GET:
 *
 * <ul>
 *   <li>{@link #listRepos()} — {@code GET /_pulsar/repos}: the repos this node hosts.
 *   <li>{@link #listOpenChanges(String)} — {@code GET /_pulsar/ledger/<repo>/changes}: the open
 *       (un-merged) changes on a repo, each carrying its own {@code revision.tip}.
 *   <li>{@link #listChangeRefs(String)} — {@code GET /_pulsar/repos/<repo>/refs}: the {@code
 *       refs/pulsar/changes/<id>} → tip-oid map. <em>Note:</em> the live node publishes only {@code
 *       refs/heads/main} and never {@code refs/pulsar/changes/*}, so the scanner resolves tips from
 *       the {@code /changes} payload instead; this method remains a valid client read but is no
 *       longer the scanner's tip source.
 * </ul>
 *
 * <p>One write, POST:
 *
 * <ul>
 *   <li>{@link #postCheck} — {@code POST /_pulsar/ledger/<repo>/changes/<changeId>/events}: append
 *       a {@code EventKind::CiStatus} change event ({@code
 *       {"kind":"ci","check":..,"conclusion":..}}) reporting a CI check result (e.g. {@code build})
 *       on an open change so the node folds it into the change's {@code required_checks} merge gate
 *       (issue #1282, convergence merge-gate payoff). The node has no dedicated {@code /checks}
 *       route — a check IS a change event.
 * </ul>
 *
 * <p>The instance is immutable + thread-safe (the JDK {@link HttpClient} is). Construct one per
 * configured node.
 */
public final class PulsarClient {

  /** Canonical ref namespace for a Pulsar open change — {@code refs/pulsar/changes/<id>}. */
  public static final String CHANGE_REF_PREFIX = "refs/pulsar/changes/";

  /** TCP connect timeout — fail fast rather than hang the scan tick. */
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  /** Per-request timeout (whole request/response). */
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private final HttpClient http;
  private final String baseUrl;

  /** Production constructor — builds a JDK {@link HttpClient} with a bounded connect timeout. */
  public PulsarClient(@NonNull String baseUrl) {
    this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), baseUrl);
  }

  /** Visible-for-testing constructor accepting an explicit {@link HttpClient}. */
  public PulsarClient(@NonNull HttpClient http, @NonNull String baseUrl) {
    this.http = Objects.requireNonNull(http, "http");
    if (Objects.requireNonNull(baseUrl, "baseUrl").isBlank()) {
      throw new IllegalArgumentException("baseUrl must not be blank");
    }
    this.baseUrl = stripTrailingSlash(baseUrl);
  }

  /** List the repos this node hosts. */
  @NonNull
  public List<String> listRepos() {
    ReposEnvelope env = get("/_pulsar/repos", ReposEnvelope.class);
    List<String> out = new ArrayList<>();
    if (env.repos != null) {
      for (RepoDto r : env.repos) {
        if (r != null && r.name != null && !r.name.isBlank()) {
          out.add(r.name);
        }
      }
    }
    return out;
  }

  /**
   * List the <em>open</em> changes on {@code repo}, each paired with its current tip oid.
   *
   * <p>The live node returns the changes as a bare JSON ARRAY ({@code [{"id":..,"status":..,
   * "revision":{"tip":..}}, ..]}), not an object envelope — so the body is parsed straight into
   * {@code ChangeDto[]}. The tip is resolved from each change's own {@code revision.tip} field
   * (live root cause: the node publishes only {@code refs/heads/main} and no {@code
   * refs/pulsar/changes/*} refs, so {@link #listChangeRefs(String)} cannot resolve a change tip;
   * the {@code /changes} payload already carries it).
   *
   * <p>An entry is returned only when {@code status == "open"} AND it has a non-blank {@code id}
   * AND a non-blank {@code revision.tip}: a {@code merged} / {@code abandoned} change must never be
   * re-triggered, and a change without a resolvable tip cannot be built. Order is preserved.
   */
  @NonNull
  public List<PulsarOpenChange> listOpenChanges(@NonNull String repo) {
    ChangeDto[] changes = get("/_pulsar/ledger/" + urlEncode(repo) + "/changes", ChangeDto[].class);
    List<PulsarOpenChange> out = new ArrayList<>();
    for (ChangeDto c : changes) {
      if (c == null || !"open".equals(c.status) || c.id == null || c.id.isBlank()) {
        continue;
      }
      String tip = c.revision == null ? null : c.revision.tip;
      if (tip == null || tip.isBlank()) {
        continue;
      }
      out.add(new PulsarOpenChange(c.id, tip));
    }
    return out;
  }

  /**
   * Read the change-ref map for {@code repo} in a SINGLE call ({@code GET
   * /_pulsar/repos/<repo>/refs}), keyed by ref name → tip oid and restricted to the {@code
   * refs/pulsar/changes/*} namespace. The scanner resolves every open change's tip via an O(1)
   * lookup against this map instead of re-fetching the full refs list per change (issue #1280
   * review: N+1 / redundant identical I/O). A change whose ref is absent has simply vanished
   * (merged / abandoned) and is left out of the map. A transport / parse failure surfaces as a
   * {@link PulsarApiException}, never a silent empty map.
   *
   * <p>The live node returns the refs as a bare JSON ARRAY ({@code [{"name":..,"oid":..}, ..]}),
   * not an object envelope — so the body is parsed straight into {@code RefDto[]} (live bug, same
   * bare-array convention #1297 fixed for {@code /changes}).
   */
  @NonNull
  public Map<String, String> listChangeRefs(@NonNull String repo) {
    RefDto[] refs = get("/_pulsar/repos/" + urlEncode(repo) + "/refs", RefDto[].class);
    Map<String, String> out = new HashMap<>();
    for (RefDto r : refs) {
      if (r != null
          && r.name != null
          && r.name.startsWith(CHANGE_REF_PREFIX)
          && r.oid != null
          && !r.oid.isBlank()) {
        out.put(r.name, r.oid);
      }
    }
    return out;
  }

  // ── write ────────────────────────────────────────────────────────────────

  /**
   * Report a CI check result on an open change so the node counts it toward the change's {@code
   * required_checks} merge gate (issue #1282). The check {@code name} MUST match an entry the
   * change's policy requires — Pulsar's {@code sample}-style repos require {@code "build"}.
   *
   * <p>The Pulsar node records a CI check as a CHANGE EVENT, not a dedicated {@code /checks}
   * resource (there is no such route — posting to it 404s, live convergence bug). It is appended
   * via {@code POST /_pulsar/ledger/<repo>/changes/<changeId>/events} and parsed by the node's
   * {@code append_event} handler under {@code EventKind::CiStatus}. The body matches that shape
   * EXACTLY:
   *
   * <pre>{@code {"kind":"ci","check":<name>,"conclusion":<pending|success|failure>}}</pre>
   *
   * where {@code kind} is the literal discriminator {@code "ci"}, {@code check} (NOT {@code name})
   * is the non-empty check name, and {@code conclusion} is exactly one of {@code pending},{@code
   * success},{@code failure} (any other token → the node answers 400). The node folds the event
   * into the change's {@code state.ci} (check→conclusion), which drives the merge gate. There is no
   * {@code details_url} field in the CI event, so it is not sent.
   *
   * <p>A non-2xx status or transport failure surfaces as a typed {@link PulsarApiException}
   * carrying the status — never a silent success (acceptance: transport/non-2xx → typed error, the
   * merge gate is never falsely cleared).
   *
   * @param repo the Pulsar repo the change lives on
   * @param changeId the change identifier (the {@code <id>} in {@code refs/pulsar/changes/<id>})
   * @param name the check name (the event's {@code check} field), matched against {@code
   *     required_checks}
   * @param conclusion the verdict folded into the gate — emitted as {@code pending|success|failure}
   * @param detailsUrl a link back to the Titan build; the node's CI event has no field for it, so
   *     it is accepted for call-site symmetry but not transmitted
   */
  public void postCheck(
      @NonNull String repo,
      @NonNull String changeId,
      @NonNull String name,
      @NonNull CheckConclusion conclusion,
      @Nullable String detailsUrl) {
    Objects.requireNonNull(repo, "repo");
    Objects.requireNonNull(changeId, "changeId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(conclusion, "conclusion");
    // EventKind::CiStatus shape — kind/check/conclusion only. LinkedHashMap for stable field order.
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", "ci");
    payload.put("check", name);
    payload.put("conclusion", conclusion.wire());
    String body;
    try {
      body = PulsarJson.MAPPER.writeValueAsString(payload);
    } catch (JsonProcessingException e) {
      // Serializing a String/String map cannot realistically fail, but never swallow it silently.
      throw new PulsarApiException(
          "pulsar check payload serialization failed: " + e.getMessage(), -1, e);
    }
    String path =
        "/_pulsar/ledger/" + urlEncode(repo) + "/changes/" + urlEncode(changeId) + "/events";
    post(path, body);
  }

  // ── transport ──────────────────────────────────────────────────────────────

  private void post(@NonNull String path, @NonNull String jsonBody) {
    URI uri = URI.create(baseUrl + path);
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
    HttpResponse<byte[]> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new PulsarApiException("pulsar POST " + path + " I/O error: " + e.getMessage(), -1, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PulsarApiException("pulsar POST " + path + " interrupted", -1, e);
    }
    int status = resp.statusCode();
    if (status < 200 || status >= 300) {
      // A non-2xx is a typed error carrying the status so the caller can categorize a transient 5xx
      // (retry) from a permanent 4xx — and so a failed post is NEVER mistaken for a cleared gate.
      throw new PulsarApiException("pulsar POST " + path + " failed: HTTP " + status, status);
    }
  }

  @NonNull
  private <T> T get(@NonNull String path, @NonNull Class<T> type) {
    URI uri = URI.create(baseUrl + path);
    HttpRequest req =
        HttpRequest.newBuilder(uri)
            .timeout(REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .GET()
            .build();
    HttpResponse<byte[]> resp;
    try {
      resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new PulsarApiException("pulsar GET " + path + " I/O error: " + e.getMessage(), -1, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PulsarApiException("pulsar GET " + path + " interrupted", -1, e);
    }
    int status = resp.statusCode();
    if (status < 200 || status >= 300) {
      // 4xx and 5xx alike: a typed error carrying the status so the scanner can log + back off
      // instead of treating an error page as "zero changes" (acceptance: not a silent empty scan).
      throw new PulsarApiException("pulsar GET " + path + " failed: HTTP " + status, status);
    }
    try {
      T parsed = PulsarJson.MAPPER.readValue(resp.body(), type);
      if (parsed == null) {
        throw new PulsarApiException("pulsar GET " + path + " returned an empty body", -1);
      }
      return parsed;
    } catch (JacksonException e) {
      throw new PulsarApiException(
          "pulsar GET " + path + " returned malformed JSON: " + e.getMessage(), -1, e);
    } catch (IOException e) {
      throw new PulsarApiException(
          "pulsar GET " + path + " body read failed: " + e.getMessage(), -1, e);
    }
  }

  @NonNull
  private static String stripTrailingSlash(@NonNull String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }

  @NonNull
  private static String urlEncode(@NonNull String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  /**
   * The verdict a {@link #postCheck} carries, folded into the change's {@code required_checks}
   * gate. A typed enum (not a bare string) so producer and consumer can never drift — Manifesto
   * rule "no stringly-typed cross-module discriminators". The {@link #wire()} form is the
   * lower-case token the Pulsar ledger records.
   */
  public enum CheckConclusion {
    /** Build started — gate stays incomplete until a terminal verdict lands. */
    PENDING("pending"),
    /** Build succeeded — satisfies the gate. */
    SUCCESS("success"),
    /** Build failed / aborted / cancelled — gate stays refused. */
    FAILURE("failure"),
    /**
     * Build finished unstable (e.g. flaky tests). The Pulsar node's CI event accepts ONLY {@code
     * pending|success|failure} (any other token → 400). An unstable build did not produce a clean
     * green, so the honest, gate-safe wire token is {@code failure}: the change stays refused
     * rather than being cleared on a non-green outcome. The Titan-side distinction is preserved as
     * a separate enum constant for richer internal mapping/logging.
     */
    UNSTABLE("failure");

    private final String wire;

    CheckConclusion(@NonNull String wire) {
      this.wire = wire;
    }

    /**
     * The wire token the Pulsar CI event records. Always one of the node-accepted set {@code
     * pending|success|failure} ({@link #UNSTABLE} collapses to {@code failure}).
     */
    @NonNull
    public String wire() {
      return wire;
    }
  }

  /**
   * One open Pulsar change paired with the tip oid to build, as returned by {@link
   * #listOpenChanges(String)}. A typed pair (not a bare {@code Map.Entry} or two parallel lists) so
   * the scanner can dedupe + emit per change without re-resolving the tip from a separate refs map.
   *
   * @param changeId the change identifier (the {@code <id>} in {@code refs/pulsar/changes/<id>})
   * @param tip the tip oid the change currently points at — guaranteed non-blank
   */
  public record PulsarOpenChange(@NonNull String changeId, @NonNull String tip) {
    public PulsarOpenChange {
      Objects.requireNonNull(changeId, "changeId");
      Objects.requireNonNull(tip, "tip");
      if (changeId.isBlank()) {
        throw new IllegalArgumentException("changeId must not be blank");
      }
      if (tip.isBlank()) {
        throw new IllegalArgumentException("tip must not be blank");
      }
    }
  }

  // ── wire DTOs (tolerant: unknown fields ignored via PulsarJson) ─────────────

  static final class ReposEnvelope {
    @JsonProperty("repos")
    List<RepoDto> repos;
  }

  static final class RepoDto {
    @JsonProperty("name")
    String name;
  }

  static final class ChangeDto {
    @JsonProperty("id")
    String id;

    @JsonProperty("status")
    String status;

    @JsonProperty("revision")
    RevisionDto revision;
  }

  static final class RevisionDto {
    @JsonProperty("base")
    String base;

    @JsonProperty("tip")
    String tip;
  }

  static final class RefDto {
    @JsonProperty("name")
    String name;

    @JsonProperty("oid")
    String oid;
  }
}
