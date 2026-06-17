package io.adaptiq.titan.scm.reconcile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.store.TitanStores;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JdbiEventDedupeStore} — the durable, DB-backed idempotency boundary over
 * {@code titan.scm_event_seen}. Backed by a real H2 {@link FakeTitanStores} so the unique-violation
 * → "already seen" path is exercised against an actual constraint, not a mock.
 */
class JdbiEventDedupeStoreTest {

  private TitanStores stores;
  private JdbiEventDedupeStore dedupe;

  @BeforeEach
  void setUp() {
    stores = FakeTitanStores.create();
    dedupe = new JdbiEventDedupeStore(stores);
  }

  @Test
  void firstClaimWins_secondIsSeen() {
    assertTrue(dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE));
    assertFalse(
        dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE),
        "a second claim of the same (provider,eventId) must report already-seen");
  }

  @Test
  void differentEventId_isFreshClaim() {
    assertTrue(dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE));
    assertTrue(
        dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o2", EventDedupeStore.Source.RECONCILE),
        "an advanced revision is a new eventId — a fresh claim");
  }

  @Test
  void sameEventId_differentProvider_isFreshClaim() {
    assertTrue(dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.WEBHOOK));
    assertTrue(
        dedupe.markSeen(ScmProvider.GITHUB, "r:c1:o1", EventDedupeStore.Source.WEBHOOK),
        "the claim is keyed by (provider,eventId) — a different provider is independent");
  }

  @Test
  void release_makesEventReclaimable() {
    assertTrue(
        dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE),
        "first claim wins");
    assertFalse(
        dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE),
        "still claimed before release");

    dedupe.release(ScmProvider.PULSAR, "r:c1:o1");

    assertTrue(
        dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE),
        "after release the same (provider,eventId) is re-claimable — a failed dispatch can retry");
    assertTrue(
        dedupe.firstSeenAt(ScmProvider.PULSAR, "r:c1:o1").isPresent(),
        "the re-claim re-records firstSeenAt");
  }

  @Test
  void release_isIdempotent_onUnclaimedEvent() {
    // Releasing a never-claimed event is a harmless no-op (0-row delete).
    dedupe.release(ScmProvider.PULSAR, "r:never:o0");
    assertTrue(
        dedupe.markSeen(ScmProvider.PULSAR, "r:never:o0", EventDedupeStore.Source.RECONCILE),
        "a never-claimed event is still claimable after a no-op release");
  }

  @Test
  void release_onlyAffectsTheTargetedKey() {
    dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE);
    dedupe.markSeen(ScmProvider.PULSAR, "r:c2:o2", EventDedupeStore.Source.RECONCILE);

    dedupe.release(ScmProvider.PULSAR, "r:c1:o1");

    assertTrue(
        dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE),
        "released key is re-claimable");
    assertFalse(
        dedupe.markSeen(ScmProvider.PULSAR, "r:c2:o2", EventDedupeStore.Source.RECONCILE),
        "a sibling claim is untouched by release of a different key");
  }

  @Test
  void firstSeenAt_recordedOnClaim() {
    dedupe.markSeen(ScmProvider.PULSAR, "r:c1:o1", EventDedupeStore.Source.RECONCILE);
    assertTrue(dedupe.firstSeenAt(ScmProvider.PULSAR, "r:c1:o1").isPresent());
    assertTrue(dedupe.firstSeenAt(ScmProvider.PULSAR, "never").isEmpty());
  }
}
