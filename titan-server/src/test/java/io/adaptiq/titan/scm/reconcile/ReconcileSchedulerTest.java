package io.adaptiq.titan.scm.reconcile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReconcileScheduler} — issue #1118 acceptance.
 *
 * <p>Covers the four spec'd behaviours:
 *
 * <ul>
 *   <li>3 new events → 3 dispatches in order; cursor advances to the last id.
 *   <li>Dedupe: a webhook-marked id is NOT re-dispatched by the reconcile loop.
 *   <li>Audit: a recovered event produces an {@code SCM_WEBHOOK_RECOVERED} row with gapSeconds.
 *   <li>Backoff: a failing repo gets skipped on the next tick; another repo still progresses.
 *   <li>Unsupported provider: the {@link ScmEventSource#supportsReconcile()} flag is honoured.
 * </ul>
 *
 * <p>Adversarial: tick() never throws even when the dispatcher throws on a specific event.
 */
class ReconcileSchedulerTest {

  private final FakeCursorStore cursors = new FakeCursorStore();
  private final FakeDedupeStore dedupe = new FakeDedupeStore();
  private final RecordingDispatcher dispatcher = new RecordingDispatcher();
  private final RecordingAuditSink auditSink = new RecordingAuditSink();
  private final ReconcileMetrics metrics = new ReconcileMetrics();
  private final Instant fixedNow = Instant.parse("2026-05-28T12:00:00Z");
  private final Clock clock = Clock.fixed(fixedNow, ZoneOffset.UTC);

  // ── happy path ─────────────────────────────────────────────────────────────

  @Test
  void tick_dispatchesNewEventsInOrderAndAdvancesCursor() {
    FakeScmEventSource source =
        new FakeScmEventSource(ScmProvider.GITHUB)
            .add(
                event(
                    ScmProvider.GITHUB,
                    "owner/repo",
                    "evt-1",
                    Instant.parse("2026-05-28T11:00:00Z")))
            .add(
                event(
                    ScmProvider.GITHUB,
                    "owner/repo",
                    "evt-2",
                    Instant.parse("2026-05-28T11:30:00Z")))
            .add(
                event(
                    ScmProvider.GITHUB,
                    "owner/repo",
                    "evt-3",
                    Instant.parse("2026-05-28T11:55:00Z")));

    ReconcileScheduler scheduler = scheduler(Map.of(ScmProvider.GITHUB, source));

    ReconcileScheduler.TickReport report =
        scheduler.tick(
            List.of(new ReconcileScheduler.RepoTarget(ScmProvider.GITHUB, "owner/repo")));

    assertEquals(
        List.of("evt-1", "evt-2", "evt-3"),
        dispatcher.dispatched.stream().map(ScmEvent::eventId).toList());
    assertEquals(3, report.recovered);
    assertEquals(Optional.of("evt-3"), cursors.lastEventId(ScmProvider.GITHUB, "owner/repo"));
    assertEquals(3L, metrics.recovered(ScmProvider.GITHUB));
    assertEquals(3, auditSink.emitted.size());
    assertEquals(3600L, auditSink.emitted.get(0).gapSeconds); // 11:00 → 12:00
  }

  // ── dedupe ─────────────────────────────────────────────────────────────────

  @Test
  void tick_skipsEventAlreadyMarkedByWebhookPath() {
    dedupe.markSeen(ScmProvider.GITHUB, "evt-2", EventDedupeStore.Source.WEBHOOK);
    FakeScmEventSource source =
        new FakeScmEventSource(ScmProvider.GITHUB)
            .add(
                event(
                    ScmProvider.GITHUB,
                    "owner/repo",
                    "evt-1",
                    Instant.parse("2026-05-28T11:00:00Z")))
            .add(
                event(
                    ScmProvider.GITHUB,
                    "owner/repo",
                    "evt-2",
                    Instant.parse("2026-05-28T11:30:00Z")))
            .add(
                event(
                    ScmProvider.GITHUB,
                    "owner/repo",
                    "evt-3",
                    Instant.parse("2026-05-28T11:55:00Z")));

    ReconcileScheduler scheduler = scheduler(Map.of(ScmProvider.GITHUB, source));

    ReconcileScheduler.TickReport report =
        scheduler.tick(
            List.of(new ReconcileScheduler.RepoTarget(ScmProvider.GITHUB, "owner/repo")));

    assertEquals(
        List.of("evt-1", "evt-3"), dispatcher.dispatched.stream().map(ScmEvent::eventId).toList());
    assertEquals(2, report.recovered);
    assertEquals(1, report.skippedDuplicate);
    assertEquals(Optional.of("evt-3"), cursors.lastEventId(ScmProvider.GITHUB, "owner/repo"));
    assertEquals(2, auditSink.emitted.size());
  }

  @Test
  void tick_reverseOrder_reconcileFirstThenWebhook_dispatchesExactlyOnce() {
    FakeScmEventSource source =
        new FakeScmEventSource(ScmProvider.GITHUB)
            .add(
                event(
                    ScmProvider.GITHUB,
                    "owner/repo",
                    "evt-X",
                    Instant.parse("2026-05-28T11:00:00Z")));
    ReconcileScheduler scheduler = scheduler(Map.of(ScmProvider.GITHUB, source));
    scheduler.tick(List.of(new ReconcileScheduler.RepoTarget(ScmProvider.GITHUB, "owner/repo")));
    assertEquals(1, dispatcher.dispatched.size());

    boolean claimed = dedupe.markSeen(ScmProvider.GITHUB, "evt-X", EventDedupeStore.Source.WEBHOOK);
    assertFalse(claimed);
    assertEquals(1, dispatcher.dispatched.size());
  }

  // ── adversarial: backoff on failure ───────────────────────────────────────

  @Test
  void tick_onListFailure_skipsRepoOnNextTickViaBackoff_otherRepoStillProgresses() {
    FailingScmEventSource failingForA =
        new FailingScmEventSource(
            ScmProvider.GITHUB,
            "owner/repo-A",
            new FakeScmEventSource(ScmProvider.GITHUB)
                .add(
                    event(
                        ScmProvider.GITHUB,
                        "owner/repo-B",
                        "evt-B1",
                        Instant.parse("2026-05-28T11:00:00Z"))));

    ReconcileScheduler scheduler = scheduler(Map.of(ScmProvider.GITHUB, failingForA));

    ReconcileScheduler.TickReport t1 =
        scheduler.tick(
            List.of(
                new ReconcileScheduler.RepoTarget(ScmProvider.GITHUB, "owner/repo-A"),
                new ReconcileScheduler.RepoTarget(ScmProvider.GITHUB, "owner/repo-B")));

    assertEquals(1, t1.failures);
    assertEquals(1, t1.recovered); // repo-B made progress despite repo-A failing
    assertEquals(1L, metrics.failures(ScmProvider.GITHUB));

    ReconcileScheduler.TickReport t2 =
        scheduler.tick(
            List.of(new ReconcileScheduler.RepoTarget(ScmProvider.GITHUB, "owner/repo-A")));
    assertEquals(1, t2.skippedBackoff);
    assertEquals(0, t2.failures);
  }

  @Test
  void tick_dispatcherThrows_tickReportsExceptionDoesNotPropagate() {
    FakeScmEventSource source =
        new FakeScmEventSource(ScmProvider.GITHUB)
            .add(
                event(
                    ScmProvider.GITHUB,
                    "owner/repo",
                    "evt-1",
                    Instant.parse("2026-05-28T11:00:00Z")));
    dispatcher.throwOn = "evt-1";

    ReconcileScheduler scheduler = scheduler(Map.of(ScmProvider.GITHUB, source));
    ReconcileScheduler.TickReport report =
        scheduler.tick(
            List.of(new ReconcileScheduler.RepoTarget(ScmProvider.GITHUB, "owner/repo")));

    assertNotNull(report);
    assertEquals(1, report.dispatchExceptions);
    assertEquals(0, report.recovered);
  }

  // ── adversarial: unsupported provider ─────────────────────────────────────

  @Test
  void tick_unsupportedProvider_skipsRepoSilently() {
    UnsupportedSource source = new UnsupportedSource(ScmProvider.BITBUCKET);
    ReconcileScheduler scheduler = scheduler(Map.of(ScmProvider.BITBUCKET, source));

    ReconcileScheduler.TickReport report =
        scheduler.tick(
            List.of(new ReconcileScheduler.RepoTarget(ScmProvider.BITBUCKET, "owner/repo")));

    assertEquals(1, report.skippedNoSource);
    assertEquals(0, report.failures);
    assertTrue(dispatcher.dispatched.isEmpty());
  }

  // ── adversarial: empty target list ────────────────────────────────────────

  @Test
  void tick_emptyTargets_noopReturnsZeroedReport() {
    ReconcileScheduler scheduler = scheduler(Map.of());
    ReconcileScheduler.TickReport report = scheduler.tick(List.of());
    assertEquals(0, report.reposChecked);
    assertEquals(0, report.recovered);
  }

  // ── adversarial: builder rejects missing collaborators ────────────────────

  @Test
  void builder_missingDeps_failsLoud() {
    assertThrows(IllegalStateException.class, () -> ReconcileScheduler.builder().build());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private ReconcileScheduler scheduler(Map<ScmProvider, ScmEventSource> sources) {
    return ReconcileScheduler.builder()
        .sources(sources)
        .cursorStore(cursors)
        .dedupe(dedupe)
        .dispatcher(dispatcher)
        .auditSink(auditSink)
        .metrics(metrics)
        .clock(clock)
        .batchCap(10)
        .backoff(Duration.ofMinutes(5))
        .build();
  }

  private static ScmEvent event(ScmProvider provider, String repo, String id, Instant when) {
    return new ScmEvent(provider, repo, id, "push", when, ("{\"id\":\"" + id + "\"}").getBytes());
  }

  // ── test doubles ───────────────────────────────────────────────────────────

  static final class FakeCursorStore implements CursorStore {
    private final Map<String, Cursor> map = new HashMap<>();

    @Override
    public Optional<String> lastEventId(ScmProvider provider, String repo) {
      Cursor c = map.get(key(provider, repo));
      return c == null ? Optional.empty() : Optional.ofNullable(c.lastEventId());
    }

    @Override
    public void advance(
        ScmProvider provider,
        String repo,
        String newEventId,
        Instant eventAt,
        Instant reconciledAt) {
      map.put(
          key(provider, repo),
          new Cursor(newEventId.isEmpty() ? null : newEventId, eventAt, reconciledAt));
    }

    @Override
    public Optional<Instant> lastReconciledAt(ScmProvider provider, String repo) {
      Cursor c = map.get(key(provider, repo));
      return c == null ? Optional.empty() : Optional.ofNullable(c.lastReconciledAt());
    }

    @Override
    public Optional<Instant> lastEventAt(ScmProvider provider, String repo) {
      Cursor c = map.get(key(provider, repo));
      return c == null ? Optional.empty() : Optional.ofNullable(c.lastEventAt());
    }

    @Override
    public Cursor read(ScmProvider provider, String repo) {
      Cursor c = map.get(key(provider, repo));
      return c == null ? new Cursor(null, null, null) : c;
    }

    private static String key(ScmProvider p, String r) {
      return p.wire() + "/" + r;
    }
  }

  static final class FakeDedupeStore implements EventDedupeStore {
    private final Map<String, Instant> seen = new HashMap<>();

    @Override
    public boolean markSeen(ScmProvider provider, String eventId, Source source) {
      return seen.putIfAbsent(key(provider, eventId), Instant.now()) == null;
    }

    @Override
    public void release(ScmProvider provider, String eventId) {
      seen.remove(key(provider, eventId));
    }

    @Override
    public Optional<Instant> firstSeenAt(ScmProvider provider, String eventId) {
      return Optional.ofNullable(seen.get(key(provider, eventId)));
    }

    private static String key(ScmProvider p, String id) {
      return p.wire() + "#" + id;
    }
  }

  static final class FakeScmEventSource implements ScmEventSource {
    private final ScmProvider provider;
    private final List<ScmEvent> events = new ArrayList<>();

    FakeScmEventSource(ScmProvider provider) {
      this.provider = provider;
    }

    FakeScmEventSource add(ScmEvent e) {
      events.add(e);
      return this;
    }

    @Override
    public ScmProvider provider() {
      return provider;
    }

    @Override
    public List<ScmEvent> listEventsSince(
        String repoExternalId, String sinceEventId, int batchCap) {
      List<ScmEvent> matching = new ArrayList<>();
      for (ScmEvent e : events) {
        if (e.repoExternalId().equals(repoExternalId)) {
          matching.add(e);
        }
      }
      if (sinceEventId == null) {
        return cap(matching, batchCap);
      }
      List<ScmEvent> after = new ArrayList<>();
      boolean past = false;
      for (ScmEvent e : matching) {
        if (past) {
          after.add(e);
        }
        if (e.eventId().equals(sinceEventId)) {
          past = true;
        }
      }
      return cap(after, batchCap);
    }

    private static List<ScmEvent> cap(List<ScmEvent> in, int cap) {
      return in.size() <= cap ? in : in.subList(0, cap);
    }
  }

  static final class FailingScmEventSource implements ScmEventSource {
    private final ScmProvider provider;
    private final String failingRepo;
    private final ScmEventSource delegate;

    FailingScmEventSource(ScmProvider provider, String failingRepo, ScmEventSource delegate) {
      this.provider = provider;
      this.failingRepo = failingRepo;
      this.delegate = delegate;
    }

    @Override
    public ScmProvider provider() {
      return provider;
    }

    @Override
    public List<ScmEvent> listEventsSince(String repoExternalId, String sinceEventId, int batchCap)
        throws ScmReconcileException {
      if (repoExternalId.equals(failingRepo)) {
        throw new ScmReconcileException(
            provider, repoExternalId, "boom", new RuntimeException("api 503"));
      }
      return delegate.listEventsSince(repoExternalId, sinceEventId, batchCap);
    }
  }

  static final class UnsupportedSource implements ScmEventSource {
    private final ScmProvider provider;

    UnsupportedSource(ScmProvider provider) {
      this.provider = provider;
    }

    @Override
    public ScmProvider provider() {
      return provider;
    }

    @Override
    public boolean supportsReconcile() {
      return false;
    }

    @Override
    public List<ScmEvent> listEventsSince(
        String repoExternalId, String sinceEventId, int batchCap) {
      throw new UnsupportedOperationException("inert");
    }
  }

  static final class RecordingDispatcher implements WebhookDispatcher {
    final List<ScmEvent> dispatched = new ArrayList<>();
    String throwOn;

    @Override
    public Outcome dispatch(ScmEvent event) {
      if (event.eventId().equals(throwOn)) {
        throw new RuntimeException("dispatch boom");
      }
      dispatched.add(event);
      return Outcome.DISPATCHED;
    }
  }

  static final class RecordingAuditSink implements ReconcileAuditSink {
    static final class Entry {
      final ScmEvent event;
      final long gapSeconds;

      Entry(ScmEvent event, long gapSeconds) {
        this.event = event;
        this.gapSeconds = gapSeconds;
      }
    }

    final List<Entry> emitted = new ArrayList<>();

    @Override
    public void recordRecovered(ScmEvent event, long gapSeconds) {
      emitted.add(new Entry(event, gapSeconds));
    }
  }
}
