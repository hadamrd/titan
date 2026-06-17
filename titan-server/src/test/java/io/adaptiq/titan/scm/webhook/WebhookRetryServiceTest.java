package io.adaptiq.titan.scm.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import io.adaptiq.titan.store.ScmWebhookEventDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ScmWebhookEventRow;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the durable webhook ingestion + retry surface (issue #1129):
 *
 * <ul>
 *   <li>{@link ScmWebhookEventDao} round-trips: insert / lookup / status transitions /
 *       findDuePending semantics.
 *   <li>{@link WebhookRetryService} happy path + sad paths (transient retry, terminal,
 *       handler-throws, budget-exhausted, unknown-provider, backoff math).
 *   <li>End-to-end "dropped webhook is recovered on the next sweep" — simulates the customer
 *       scenario in the issue (transient handler failure → row stays PENDING → second tick succeeds
 *       → status PROCESSED).
 * </ul>
 *
 * <p>Backed by an in-memory H2 via {@code FakeTitanStores} so the unique-key dedupe + index hits
 * are exercised against real SQL, not a mock.
 */
class WebhookRetryServiceTest {

  private TitanStores stores;
  private ScmWebhookEventDao dao;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);
    dao = stores.scmWebhookEvents();
  }

  // ── DAO: insert + dedupe ────────────────────────────────────────────────────

  @Test
  void insertPending_thenFindByDelivery_roundTrips() {
    long id =
        dao.insertPending(
            ScmProvider.GITHUB.wire(), "delivery-1", "push", "{\"x\":1}", "sha256=deadbeef");
    Optional<ScmWebhookEventRow> found = dao.findByDelivery("github", "delivery-1");
    assertTrue(found.isPresent());
    ScmWebhookEventRow row = found.get();
    assertEquals(id, row.id);
    assertEquals("PENDING", row.status);
    assertEquals(0, row.attempts);
    assertEquals("push", row.eventType);
    assertEquals("{\"x\":1}", row.payload);
    assertNotNull(row.receivedAt);
    assertNull(row.nextAttemptAt);
  }

  @Test
  void insertPending_secondInsertForSameDelivery_violatesUniqueConstraint() {
    dao.insertPending("github", "delivery-dup", "push", "{}", null);
    // Adversarial: a re-delivery from GitHub MUST NOT create a second row. The unique key on
    // (provider, delivery_id) is the durable dedupe boundary.
    assertThrows(
        RuntimeException.class,
        () -> dao.insertPending("github", "delivery-dup", "push", "{}", null));
  }

  @Test
  void insertPending_sameDeliveryIdAcrossProviders_isAllowed() {
    // GitHub and GitLab both call their UUID 'delivery-1' — these are distinct rows.
    dao.insertPending("github", "delivery-1", "push", "{}", null);
    dao.insertPending("gitlab", "delivery-1", "push", "{}", null);
    assertTrue(dao.findByDelivery("github", "delivery-1").isPresent());
    assertTrue(dao.findByDelivery("gitlab", "delivery-1").isPresent());
  }

  // ── DAO: status transitions ─────────────────────────────────────────────────

  @Test
  void markProcessed_setsStatusAndClearsRetryFields() {
    long id = dao.insertPending("github", "d1", "push", "{}", null);
    dao.markFailedWithRetry(id, "boom", Instant.now().plusSeconds(60));
    assertEquals(1, dao.markProcessed(id));
    ScmWebhookEventRow row = dao.findById(id).orElseThrow();
    assertEquals("PROCESSED", row.status);
    assertNull(row.lastError);
    assertNull(row.nextAttemptAt);
  }

  @Test
  void markProcessed_isIdempotent() {
    long id = dao.insertPending("github", "d1", "push", "{}", null);
    assertEquals(1, dao.markProcessed(id));
    // Second call is a no-op (WHERE status <> 'PROCESSED' filters it out) — operator-friendly.
    assertEquals(0, dao.markProcessed(id));
  }

  @Test
  void findDuePending_picksUpRowsWithNullNextAttempt() {
    long id = dao.insertPending("github", "d1", "push", "{}", null);
    List<ScmWebhookEventRow> due = dao.findDuePending(Instant.now(), 10);
    assertEquals(1, due.size());
    assertEquals(id, due.get(0).id);
  }

  @Test
  void findDuePending_excludesFutureScheduled() {
    long id = dao.insertPending("github", "d1", "push", "{}", null);
    dao.markFailedWithRetry(id, "transient", Instant.now().plusSeconds(3600));
    List<ScmWebhookEventRow> due = dao.findDuePending(Instant.now(), 10);
    assertTrue(due.isEmpty(), "row scheduled an hour out must not be picked up now");
  }

  @Test
  void findDuePending_excludesTerminallyFailedRows() {
    long id = dao.insertPending("github", "d1", "push", "{}", null);
    dao.markFailedTerminal(id, "bad payload");
    assertTrue(dao.findDuePending(Instant.now().plusSeconds(1_000_000), 10).isEmpty());
    assertEquals(1, dao.countFailed());
  }

  // ── WebhookRetryService: happy path ─────────────────────────────────────────

  @Test
  void tick_successResult_marksProcessed() {
    long id = dao.insertPending("github", "d-ok", "push", "{}", null);
    WebhookRetryService svc =
        new WebhookRetryService(
            dao,
            new ScriptedHandler(WebhookHandler.Result.success()),
            3,
            10,
            Duration.ofSeconds(1));
    WebhookRetryService.TickStats stats = svc.tick(Instant.now());
    assertEquals(1, stats.considered());
    assertEquals(1, stats.processed());
    assertEquals(0, stats.retried());
    assertEquals(0, stats.terminal());
    assertEquals("PROCESSED", dao.findById(id).orElseThrow().status);
  }

  // ── WebhookRetryService: customer scenario — dropped webhook recovered ─────

  @Test
  void tick_transientFailureThenSuccess_recoversOnSecondTick() {
    // The customer scenario verbatim: webhook arrives, downstream is flaky, first attempt fails,
    // sweeper retries on the next tick and the build is enqueued. No silent build gap.
    long id = dao.insertPending("github", "d-flaky", "push", "{}", null);
    ScriptedHandler handler =
        new ScriptedHandler(
            WebhookHandler.Result.retry("downstream-503"), WebhookHandler.Result.success());
    WebhookRetryService svc = new WebhookRetryService(dao, handler, 5, 10, Duration.ofMillis(1));

    Instant t0 = Instant.now();
    WebhookRetryService.TickStats first = svc.tick(t0);
    assertEquals(1, first.retried());
    ScmWebhookEventRow afterFirst = dao.findById(id).orElseThrow();
    assertEquals("PENDING", afterFirst.status);
    assertEquals(1, afterFirst.attempts);
    assertEquals("downstream-503", afterFirst.lastError);
    assertNotNull(afterFirst.nextAttemptAt);

    // Second tick after the back-off window: the now-healthy handler returns SUCCESS.
    WebhookRetryService.TickStats second = svc.tick(t0.plusSeconds(10));
    assertEquals(1, second.processed());
    assertEquals("PROCESSED", dao.findById(id).orElseThrow().status);
  }

  // ── WebhookRetryService: sad paths ─────────────────────────────────────────

  @Test
  void tick_retryBudgetExhausted_marksTerminal() {
    long id = dao.insertPending("github", "d-doomed", "push", "{}", null);
    // attempts already at 2; maxAttempts = 3 ⇒ nextAttempt would be 3 ⇒ terminal.
    dao.markFailedWithRetry(id, "prev", Instant.EPOCH);
    dao.markFailedWithRetry(id, "prev", Instant.EPOCH);
    WebhookRetryService svc =
        new WebhookRetryService(
            dao,
            new ScriptedHandler(WebhookHandler.Result.retry("still flaky")),
            3,
            10,
            Duration.ofSeconds(1));
    WebhookRetryService.TickStats stats = svc.tick(Instant.now());
    assertEquals(1, stats.terminal());
    ScmWebhookEventRow row = dao.findById(id).orElseThrow();
    assertEquals("FAILED", row.status);
    assertTrue(row.lastError.contains("retry-budget-exhausted"));
  }

  @Test
  void tick_terminalResult_marksTerminalImmediately() {
    long id = dao.insertPending("github", "d-bad", "push", "not-json", null);
    WebhookRetryService svc =
        new WebhookRetryService(
            dao,
            new ScriptedHandler(WebhookHandler.Result.terminal("payload not JSON")),
            5,
            10,
            Duration.ofSeconds(1));
    svc.tick(Instant.now());
    ScmWebhookEventRow row = dao.findById(id).orElseThrow();
    assertEquals("FAILED", row.status);
    assertEquals("payload not JSON", row.lastError);
  }

  @Test
  void tick_handlerThrows_isTreatedAsRetry() {
    long id = dao.insertPending("github", "d-throw", "push", "{}", null);
    WebhookRetryService svc =
        new WebhookRetryService(
            dao,
            (provider, row) -> {
              throw new RuntimeException("kaboom");
            },
            5,
            10,
            Duration.ofSeconds(1));
    svc.tick(Instant.now());
    ScmWebhookEventRow row = dao.findById(id).orElseThrow();
    assertEquals("PENDING", row.status);
    assertEquals(1, row.attempts);
    assertTrue(row.lastError.contains("kaboom"));
  }

  @Test
  void tick_unknownProvider_marksTerminalWithoutCallingHandler() {
    // Adversarial: a row whose provider string doesn't map to the ScmProvider enum must not
    // pin the sweeper in a retry loop forever. Manifesto §"No stringly-typed cross-module
    // discriminators" — unknown string is a corruption signal we surface to operators.
    long id = dao.insertPending("rogue-provider", "d-x", "push", "{}", null);
    boolean[] called = {false};
    WebhookRetryService svc =
        new WebhookRetryService(
            dao,
            (p, r) -> {
              called[0] = true;
              return WebhookHandler.Result.success();
            },
            5,
            10,
            Duration.ofSeconds(1));
    svc.tick(Instant.now());
    assertFalse(called[0], "handler must not be invoked for an unknown provider row");
    ScmWebhookEventRow row = dao.findById(id).orElseThrow();
    assertEquals("FAILED", row.status);
    assertTrue(row.lastError.contains("unknown provider"));
  }

  @Test
  void tick_emptyBacklog_isANoOp() {
    WebhookRetryService svc =
        new WebhookRetryService(
            dao,
            new ScriptedHandler(WebhookHandler.Result.success()),
            3,
            10,
            Duration.ofSeconds(1));
    WebhookRetryService.TickStats stats = svc.tick(Instant.now());
    assertEquals(0, stats.considered());
    assertEquals(0, stats.processed());
  }

  @Test
  void tick_respectsBatchSize() {
    for (int i = 0; i < 5; i++) {
      dao.insertPending("github", "batch-" + i, "push", "{}", null);
    }
    WebhookRetryService svc =
        new WebhookRetryService(
            dao, new ScriptedHandler(WebhookHandler.Result.success()), 3, 2, Duration.ofSeconds(1));
    WebhookRetryService.TickStats stats = svc.tick(Instant.now());
    assertEquals(2, stats.considered(), "batch size cap must bound rows per tick");
  }

  // ── Backoff math ───────────────────────────────────────────────────────────

  @Test
  void backoffFor_grows_exponentially_andClampsAtMax() {
    WebhookRetryService svc =
        new WebhookRetryService(
            dao,
            new ScriptedHandler(WebhookHandler.Result.success()),
            10,
            10,
            Duration.ofSeconds(1));
    assertEquals(Duration.ofSeconds(1), svc.backoffFor(1));
    assertEquals(Duration.ofSeconds(2), svc.backoffFor(2));
    assertEquals(Duration.ofSeconds(4), svc.backoffFor(3));
    // Anything past the cap is clamped — no near-infinite delays.
    assertEquals(WebhookRetryService.MAX_BACKOFF, svc.backoffFor(40));
  }

  @Test
  void constructor_rejectsInvalidArgs() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebhookRetryService(
                dao,
                new ScriptedHandler(WebhookHandler.Result.success()),
                0,
                1,
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebhookRetryService(
                dao,
                new ScriptedHandler(WebhookHandler.Result.success()),
                1,
                0,
                Duration.ofSeconds(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new WebhookRetryService(
                dao, new ScriptedHandler(WebhookHandler.Result.success()), 1, 1, Duration.ZERO));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * Deterministic, per-test handler that returns scripted results on consecutive {@code handle}
   * calls. The last result is sticky (replayed for subsequent calls) so tests can write "one
   * failure then steady-state success" without enumerating every tick.
   */
  private static final class ScriptedHandler implements WebhookHandler {
    private final Deque<Result> scripted = new ArrayDeque<>();
    private Result last;

    ScriptedHandler(Result... results) {
      for (Result r : results) {
        scripted.add(r);
      }
      last = results[results.length - 1];
    }

    @Override
    @NonNull
    public Result handle(@NonNull ScmProvider provider, @NonNull ScmWebhookEventRow row) {
      Result next = scripted.poll();
      if (next == null) {
        return last;
      }
      last = next;
      return next;
    }
  }
}
