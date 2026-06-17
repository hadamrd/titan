package io.adaptiq.titan.trigger.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.adaptiq.titan.trigger.CronTrigger;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerContext;
import io.adaptiq.titan.trigger.TriggerEvent;
import io.adaptiq.titan.trigger.TriggerOutcome;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Ported from {@code io.adaptiq.scheduler.engine.TriggerDispatchTest} (Wave 1). */
class TriggerDispatchTest {

  private static final Instant T1 = Instant.parse("2026-06-15T12:00:30Z");
  private static final Instant T2 = Instant.parse("2026-06-15T12:01:30Z");

  @Test
  void firstSightRecordsBaselineWithoutFiring() {
    FakeScope scope = new FakeScope();
    FakeOwner owner = owner("o1", new FakeTrigger("t1", TriggerOutcome.fire()));
    dispatch(owner, scope, T1);

    assertEquals(0, scope.fireCount, "a trigger never fires on first sight");
    assertEquals(T1, scope.lastFired.get("t1"), "first sight stamps the catch-up baseline");
  }

  @Test
  void firesOnceABaselineExists() {
    FakeScope scope = new FakeScope();
    FakeOwner owner = owner("o1", new FakeTrigger("t1", TriggerOutcome.fire()));
    dispatch(owner, scope, T1);
    dispatch(owner, scope, T2);

    assertEquals(1, scope.fireCount);
    assertEquals(T2, scope.lastFired.get("t1"), "a satisfied occurrence is stamped at `now`");
  }

  @Test
  void coalescesWhenABuildIsAlreadyInFlight() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("t1", T1);
    scope.inFlight = true;
    FakeOwner owner = owner("o1", new FakeTrigger("t1", TriggerOutcome.fire()));
    dispatch(owner, scope, T2);

    assertEquals(0, scope.fireCount, "an in-flight build absorbs the occurrence");
    assertEquals(T2, scope.lastFired.get("t1"), "the occurrence is still stamped");
  }

  @Test
  void filterCanVetoAFire() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("t1", T1);
    FakeOwner owner = owner("o1", new FakeTrigger("t1", TriggerOutcome.fire()));
    dispatch(List.of(owner), scope, List.of(downgradeFilter()), T2);

    assertEquals(0, scope.fireCount, "the filter downgraded FIRE to SKIP");
    assertEquals(T1, scope.lastFired.get("t1"), "a vetoed occurrence stamps nothing");
  }

  @Test
  void filterCanDeferAFire() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("t1", T1);
    FakeOwner owner = owner("o1", new FakeTrigger("t1", TriggerOutcome.fire()));
    TriggerFilter defer = (o, t, proposed, now) -> TriggerOutcome.defer(now.plusSeconds(600));
    dispatch(List.of(owner), scope, List.of(defer), T2);

    assertEquals(0, scope.fireCount, "a DEFER from a filter suppresses the fire");
    assertEquals(T1, scope.lastFired.get("t1"), "DEFER stamps nothing");
  }

  @Test
  void filtersChainInOrderEachSeeingThePriorOutcome() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("t1", T1);
    FakeOwner owner = owner("o1", new FakeTrigger("t1", TriggerOutcome.fire()));

    TriggerOutcome.Kind[] seenBySecond = new TriggerOutcome.Kind[1];
    TriggerFilter first = (o, t, proposed, now) -> TriggerOutcome.skip("first vetoes");
    TriggerFilter second =
        (o, t, proposed, now) -> {
          seenBySecond[0] = proposed.kind();
          return proposed;
        };
    dispatch(List.of(owner), scope, List.of(first, second), T2);

    assertEquals(
        TriggerOutcome.Kind.SKIP,
        seenBySecond[0],
        "the second filter sees the first filter's output, not the raw trigger outcome");
    assertEquals(0, scope.fireCount);
  }

  @Test
  void aThrowingFilterIsIgnoredAndTheFireStillProceeds() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("t1", T1);
    FakeOwner owner = owner("o1", new FakeTrigger("t1", TriggerOutcome.fire()));
    TriggerFilter broken =
        (o, t, proposed, now) -> {
          throw new IllegalStateException("filter blew up");
        };
    dispatch(List.of(owner), scope, List.of(broken), T2);

    assertEquals(
        1,
        scope.fireCount,
        "a broken filter is isolated — it must not block an otherwise-due fire");
  }

  @Test
  void aSecondDueTriggerCoalescesAgainstTheFirstsBuild() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("t1", T1);
    scope.lastFired.put("t2", T1);
    FakeOwner owner =
        owner(
            "o1",
            new FakeTrigger("t1", TriggerOutcome.fire()),
            new FakeTrigger("t2", TriggerOutcome.fire()));
    dispatch(owner, scope, T2);

    assertEquals(
        1, scope.fireCount, "two triggers due on one owner in one tick yield a single build");
    assertEquals(T2, scope.lastFired.get("t1"));
    assertEquals(
        T2, scope.lastFired.get("t2"), "the coalesced trigger still stamps its occurrence");
  }

  @Test
  void deferPersistsNothing() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("t1", T1);
    FakeOwner owner =
        owner("o1", new FakeTrigger("t1", TriggerOutcome.defer(T2.plus(Duration.ofHours(1)))));
    dispatch(owner, scope, T2);

    assertEquals(0, scope.fireCount);
    assertEquals(T1, scope.lastFired.get("t1"), "DEFER leaves last-fired untouched");
  }

  @Test
  void disabledOwnerIsSkippedEntirely() {
    FakeScope scope = new FakeScope();
    FakeOwner owner =
        new FakeOwner("o1", false, List.of(new FakeTrigger("t1", TriggerOutcome.fire())));
    dispatch(List.of(owner), scope, List.of(), T1);

    assertEquals(0, scope.fireCount);
    assertTrue(scope.lastFired.isEmpty(), "a disabled owner is never even baselined");
  }

  @Test
  void aThrowingTriggerIsIsolatedAndRecordsAnError() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("t1", T1);
    FakeOwner owner = owner("o1", FakeTrigger.throwing("t1"));
    dispatch(owner, scope, T2);

    assertEquals(0, scope.fireCount);
    assertTrue(scope.errors.containsKey("t1"), "the failure is recorded for diagnostics");
  }

  @Test
  void multipleOwnersAreEvaluatedIndependently() {
    FakeStore store = new FakeStore();
    store.scopeFor("o1").lastFired.put("t1", T1);
    store.scopeFor("o2").lastFired.put("t2", T1);
    List<TriggerOwner> owners =
        List.of(
            owner("o1", new FakeTrigger("t1", TriggerOutcome.fire())),
            owner("o2", new FakeTrigger("t2", TriggerOutcome.fire())));

    TriggerDispatch.evaluate(new FakeSubsystem(owners, store), List.of(), T2);

    assertEquals(1, store.scopeFor("o1").fireCount);
    assertEquals(1, store.scopeFor("o2").fireCount);
  }

  @Test
  void aSecondEvaluationInTheSameMinuteDoesNotDoubleFire() {
    FakeScope scope = new FakeScope();
    Instant now = Instant.parse("2026-06-15T12:05:30Z");
    scope.lastFired.put("c", now.minus(Duration.ofMinutes(2)));
    FakeOwner owner = owner("o1", new CronTrigger("c", "* * * * *"));

    dispatch(owner, scope, now);
    assertEquals(1, scope.fireCount, "due — fires once");

    dispatch(owner, scope, now);
    assertEquals(1, scope.fireCount, "the advanced last-fired time blocks a double fire");
  }

  @Test
  void aCronTriggerIgnoresAnEventDelivery() {
    FakeScope scope = new FakeScope();
    FakeOwner owner = owner("o1", new CronTrigger("c", "H 2 * * *"));
    dispatchEvent(owner, scope, TriggerEvent.webhook("tok", Map.of()));

    assertEquals(0, scope.fireCount, "a cron trigger does not react to a pushed event");
  }

  @Test
  void aHangingTriggerUnderABoundedEvaluatorDoesNotWedgeThePass() {
    FakeScope scope = new FakeScope();
    scope.lastFired.put("h", T1);
    FakeOwner owner = owner("o1", FakeTrigger.hanging("h"));
    BoundedTriggerEvaluator bounded = new BoundedTriggerEvaluator(() -> Duration.ofMillis(100));

    assertTimeoutPreemptively(
        Duration.ofSeconds(3),
        () -> {
          FakeStore store = new FakeStore();
          store.scopes.put(owner.ownerId(), scope);
          TriggerDispatch.evaluate(
              new FakeSubsystem(List.of(owner), store), List.of(), T2, null, bounded);
        });
    assertEquals(0, scope.fireCount, "the hung trigger timed out → SKIP → no build");
  }

  // ── fakes ───────────────────────────────────────────────────────────────

  private static void dispatchEvent(FakeOwner owner, FakeScope scope, TriggerEvent event) {
    FakeStore store = new FakeStore();
    store.scopes.put(owner.ownerId(), scope);
    TriggerDispatch.evaluate(
        new FakeSubsystem(List.of(owner), store), List.of(), event.instant(), event);
  }

  private static void dispatch(FakeOwner owner, FakeScope scope, Instant now) {
    dispatch(List.of(owner), scope, List.of(), now);
  }

  private static void dispatch(
      List<TriggerOwner> owners, FakeScope scope, List<TriggerFilter> filters, Instant now) {
    FakeStore store = new FakeStore();
    for (TriggerOwner o : owners) {
      store.scopes.put(o.ownerId(), scope);
    }
    TriggerDispatch.evaluate(new FakeSubsystem(owners, store), filters, now);
  }

  private static FakeOwner owner(String id, Trigger... triggers) {
    return new FakeOwner(id, true, List.of(triggers));
  }

  private static TriggerFilter downgradeFilter() {
    return (o, t, proposed, now) -> TriggerOutcome.skip("vetoed by test filter");
  }

  static final class FakeTrigger extends Trigger {
    private final TriggerOutcome outcome;
    private final boolean throwing;
    private final boolean hanging;

    FakeTrigger(String id, TriggerOutcome outcome) {
      this(id, outcome, false, false);
    }

    private FakeTrigger(String id, TriggerOutcome outcome, boolean throwing, boolean hanging) {
      super(id);
      this.outcome = outcome;
      this.throwing = throwing;
      this.hanging = hanging;
    }

    static FakeTrigger throwing(String id) {
      return new FakeTrigger(id, null, true, false);
    }

    static FakeTrigger hanging(String id) {
      return new FakeTrigger(id, TriggerOutcome.fire(), false, true);
    }

    @Override
    public String getType() {
      return "fake";
    }

    @Override
    public TriggerOutcome evaluate(TriggerContext ctx) {
      if (throwing) {
        throw new IllegalStateException("boom");
      }
      if (hanging) {
        long deadline = System.nanoTime() + Duration.ofMinutes(1).toNanos();
        while (System.nanoTime() < deadline) {
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      }
      return outcome;
    }

    @Override
    public void writeState(ObjectNode node) {
      // a test trigger has no persisted state
    }
  }

  record FakeOwner(String ownerId, boolean schedulingEnabled, List<Trigger> triggers)
      implements TriggerOwner {
    @Override
    public String hashSeed() {
      return ownerId;
    }
  }

  record FakeSubsystem(Collection<TriggerOwner> owners, TriggerStore store)
      implements TriggerSubsystem {}

  static final class FakeStore implements TriggerStore {
    final Map<String, FakeScope> scopes = new HashMap<>();

    FakeScope scopeFor(String ownerId) {
      return scopes.computeIfAbsent(ownerId, k -> new FakeScope());
    }

    @Override
    public void inLockedScope(TriggerOwner owner, Consumer<TriggerScope> work) {
      work.accept(scopeFor(owner.ownerId()));
    }
  }

  static final class FakeScope implements TriggerScope {
    final Map<String, Instant> lastFired = new HashMap<>();
    final Map<String, String> errors = new HashMap<>();
    boolean inFlight;
    int fireCount;

    @Override
    public Instant lastFiredAt(String triggerId) {
      return lastFired.get(triggerId);
    }

    @Override
    public boolean buildInFlight() {
      return inFlight;
    }

    @Override
    public void fire() {
      fireCount++;
      inFlight = true;
    }

    @Override
    public void recordFired(String triggerId, Instant when) {
      lastFired.put(triggerId, when);
    }

    @Override
    public void recordError(String triggerId, String message) {
      errors.put(triggerId, message);
    }
  }
}
