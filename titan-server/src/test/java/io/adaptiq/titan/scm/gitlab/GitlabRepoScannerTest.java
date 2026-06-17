package io.adaptiq.titan.scm.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.scm.reconcile.ScmEvent;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.scm.reconcile.ScmReconcileException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GitlabRepoScanner} — issue #1134 acceptance.
 *
 * <p>Covers the spec'd diff logic + adversarial failure paths:
 *
 * <ul>
 *   <li>Happy path: API returns N events → N ScmEvents in order, ascending by occurredAt.
 *   <li>Cursor diff: already-known events (i.e. {@code sinceEventId} hop) are filtered upstream by
 *       the factory; the source returns only what the factory returned.
 *   <li>Adversarial: HTTP 401 from GitLab is wrapped as a {@link ScmReconcileException} (the
 *       scheduler then logs + backs off — does NOT crash).
 *   <li>Adversarial: HTTP 500 → same, with the status preserved on the cause.
 *   <li>Conditional GET: a non-304 response with an ETag header populates the in-memory ETag cache;
 *       the next call passes that ETag back as {@code If-None-Match}.
 *   <li>304 Not Modified: the source returns an empty event list AND the cached ETag is preserved
 *       (so the next tick still issues a conditional GET).
 * </ul>
 */
class GitlabRepoScannerTest {

  private static final Instant T0 = Instant.parse("2026-05-28T10:00:00Z");

  // ── happy path ─────────────────────────────────────────────────────────────

  @Test
  void listEventsSince_emitsAllFactoryEventsAsScmEventsInOrder() throws Exception {
    FakeGitlabClientFactory factory =
        new FakeGitlabClientFactory()
            .addEvent("123", event("1001", "push", T0, "refs/heads/main", "aaa"))
            .addEvent("123", event("1002", "push", T0.plusSeconds(60), "refs/heads/main", "bbb"))
            .addEvent("123", event("1003", "push", T0.plusSeconds(120), "refs/heads/dev", "ccc"));

    GitlabRepoScanner scanner = new GitlabRepoScanner(factory);
    List<ScmEvent> events = scanner.listEventsSince("123", null, 50);

    assertEquals(3, events.size());
    assertEquals("1001", events.get(0).eventId());
    assertEquals("1002", events.get(1).eventId());
    assertEquals("1003", events.get(2).eventId());
    assertEquals(ScmProvider.GITLAB, events.get(0).provider());
    assertEquals("push", events.get(0).eventType());
  }

  @Test
  void listEventsSince_skipsAlreadyBuilt_factoryRespectsSinceCursor() throws Exception {
    // The factory honours the sinceEventId cursor — the scanner just trusts what comes back.
    // Adversarial twist: the upstream "owned" SHA filter lives in the factory; the scanner under
    // test must NOT redundantly filter (that would double-skip if the cursor was stale).
    FakeGitlabClientFactory factory =
        new FakeGitlabClientFactory()
            .addEvent("123", event("1001", "push", T0, "refs/heads/main", "aaa"))
            .addEvent("123", event("1002", "push", T0.plusSeconds(60), "refs/heads/main", "bbb"))
            .addEvent("123", event("1003", "push", T0.plusSeconds(120), "refs/heads/dev", "ccc"));

    GitlabRepoScanner scanner = new GitlabRepoScanner(factory);
    List<ScmEvent> events = scanner.listEventsSince("123", "1001", 50);

    assertEquals(2, events.size());
    assertEquals("1002", events.get(0).eventId());
    assertEquals("1003", events.get(1).eventId());
  }

  // ── adversarial: auth failure does NOT crash the scheduler ─────────────────

  @Test
  void listEventsSince_authFailure401_isWrappedAsScmReconcileException() {
    FakeGitlabClientFactory factory =
        new FakeGitlabClientFactory()
            .failNextWith(new GitlabApiException("auth failed", 401, null));
    GitlabRepoScanner scanner = new GitlabRepoScanner(factory);

    ScmReconcileException thrown =
        assertThrows(
            ScmReconcileException.class,
            () -> scanner.listEventsSince("123", null, 50),
            "401 must surface as a typed ScmReconcileException so the scheduler can apply backoff");
    assertEquals(ScmProvider.GITLAB, thrown.provider());
    assertEquals("123", thrown.repoExternalId());
    assertNotNull(thrown.getCause(), "cause carries the underlying GitlabApiException");
    assertTrue(thrown.getCause() instanceof GitlabApiException);
    assertEquals(401, ((GitlabApiException) thrown.getCause()).status());
  }

  @Test
  void listEventsSince_transientHttp500_isWrappedAndStatusPreserved() {
    FakeGitlabClientFactory factory =
        new FakeGitlabClientFactory()
            .failNextWith(new GitlabApiException("gateway", 500, new RuntimeException("upstream")));
    GitlabRepoScanner scanner = new GitlabRepoScanner(factory);

    ScmReconcileException thrown =
        assertThrows(ScmReconcileException.class, () -> scanner.listEventsSince("9", null, 5));
    assertEquals(500, ((GitlabApiException) thrown.getCause()).status());
  }

  // ── conditional-GET / If-None-Match wiring ─────────────────────────────────

  @Test
  void listEventsSince_persistsEtagAcrossCalls_andSendsItOnTheNextTick() throws Exception {
    FakeGitlabClientFactory factory =
        new FakeGitlabClientFactory()
            .nextEtag("\"abc\"")
            .addEvent("99", event("1", "push", T0, "refs/heads/main", "deadbeef"));

    GitlabRepoScanner scanner = new GitlabRepoScanner(factory);
    scanner.listEventsSince("99", null, 50);

    assertEquals("\"abc\"", scanner.etagFor("99"));
    // Second call must echo the ETag back as If-None-Match.
    factory.nextEtag("\"abc\"");
    factory.nextNotModified();
    List<ScmEvent> events = scanner.listEventsSince("99", "1", 50);
    assertTrue(events.isEmpty(), "304 Not Modified -> empty event list");
    assertEquals("\"abc\"", factory.lastIfNoneMatch);
    // ETag MUST be preserved across a 304 — the next tick stays conditional.
    assertEquals("\"abc\"", scanner.etagFor("99"));
  }

  // ── provider plumbing ─────────────────────────────────────────────────────

  @Test
  void provider_isGitlab_andReconcileSupported() {
    GitlabRepoScanner scanner = new GitlabRepoScanner(new FakeGitlabClientFactory());
    assertEquals(ScmProvider.GITLAB, scanner.provider());
    assertTrue(scanner.supportsReconcile());
  }

  // ── adversarial: empty page returns empty list, no ETag churn ─────────────

  @Test
  void listEventsSince_emptyPage_returnsEmpty_andDoesNotPopulateEtagIfHeaderAbsent()
      throws Exception {
    FakeGitlabClientFactory factory = new FakeGitlabClientFactory(); // empty, no etag
    GitlabRepoScanner scanner = new GitlabRepoScanner(factory);
    List<ScmEvent> events = scanner.listEventsSince("7", null, 5);
    assertTrue(events.isEmpty());
    assertNull(scanner.etagFor("7"));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static GitlabEventDto event(
      String id, String type, Instant when, String ref, String sha) {
    return new GitlabEventDto(id, type, when, ref, sha, ("{\"id\":" + id + "}").getBytes());
  }

  /**
   * Deterministic in-memory {@link GitlabClientFactory} — the canonical Fake required by the
   * Manifesto §"External I/O" rule. Honours the {@code sinceEventId} cursor so the scanner can
   * trust factory output without re-filtering.
   */
  static final class FakeGitlabClientFactory implements GitlabClientFactory {
    private final Map<String, List<GitlabEventDto>> events = new ConcurrentHashMap<>();
    private String nextEtag;
    private boolean nextNotModified;
    private GitlabApiException nextFailure;
    @edu.umd.cs.findbugs.annotations.Nullable String lastIfNoneMatch;

    FakeGitlabClientFactory addEvent(String projectId, GitlabEventDto event) {
      events.computeIfAbsent(projectId, k -> new ArrayList<>()).add(event);
      return this;
    }

    FakeGitlabClientFactory nextEtag(String etag) {
      this.nextEtag = etag;
      return this;
    }

    FakeGitlabClientFactory nextNotModified() {
      this.nextNotModified = true;
      return this;
    }

    FakeGitlabClientFactory failNextWith(GitlabApiException e) {
      this.nextFailure = e;
      return this;
    }

    @Override
    public PageResult listProjectEvents(
        String projectId, String sinceEventId, String ifNoneMatch, int limit)
        throws GitlabApiException {
      this.lastIfNoneMatch = ifNoneMatch;
      if (nextFailure != null) {
        GitlabApiException e = nextFailure;
        nextFailure = null;
        throw e;
      }
      String etag = nextEtag;
      nextEtag = null;
      if (nextNotModified) {
        nextNotModified = false;
        return new PageResult(List.of(), etag != null ? etag : ifNoneMatch, true);
      }
      List<GitlabEventDto> all = events.getOrDefault(projectId, List.of());
      List<GitlabEventDto> filtered = new ArrayList<>();
      boolean past = sinceEventId == null;
      for (GitlabEventDto e : all) {
        if (!past) {
          if (e.eventId().equals(sinceEventId)) {
            past = true;
          }
          continue;
        }
        if (e.eventId().equals(sinceEventId)) {
          continue;
        }
        filtered.add(e);
      }
      return new PageResult(filtered, etag, false);
    }
  }
}
