package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.store.rows.AuditRetentionPolicyRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link AuditRetentionPolicyDao#upsert(String, int)} (#1104, #1196 review).
 *
 * <p>Two guarantees:
 *
 * <ul>
 *   <li><strong>Idempotent</strong> — a second upsert on a kind that already has a row UPDATEs it
 *       (no PK-violation, last value wins). Exercised against a real H2-backed DAO via {@link
 *       FakeTitanStores} so the actual SQL runs.
 *   <li><strong>TOCTOU-safe</strong> — the UPDATE→INSERT window is racy: a concurrent writer can
 *       insert the same kind after our UPDATE→0, so our INSERT loses the PK race. {@code upsert}
 *       must recover by re-running the UPDATE rather than letting the failure escape as a 500. The
 *       race is forced deterministically with a fake DAO whose first UPDATE returns 0, whose INSERT
 *       throws (the lost race), and whose retry UPDATE returns 1 (the winner's row now exists).
 *   <li><strong>Non-race failures still propagate</strong> — if the retry UPDATE still affects 0
 *       rows the original INSERT failure was not a lost race (e.g. a CHECK violation), so it is
 *       rethrown unchanged.
 * </ul>
 */
class AuditRetentionPolicyDaoTest {

  // ── real-DAO idempotency (H2) ──────────────────────────────────────────────

  @Test
  void upsertIsIdempotent_secondCallUpdatesInsteadOfFailingThePk() {
    TitanStores stores = FakeTitanStores.create();
    AuditRetentionPolicyDao dao = stores.auditRetentionPolicy();

    dao.upsert("PIPELINE_EDIT", 100);
    // Second upsert on the SAME kind must not throw a PK violation — it updates in place.
    dao.upsert("PIPELINE_EDIT", 200);

    Optional<AuditRetentionPolicyRow> row = dao.findByKind("PIPELINE_EDIT");
    assertTrue(row.isPresent(), "the row exists after two upserts");
    assertEquals(200, row.get().maxAgeDays, "last write wins on a repeated upsert");
    assertEquals(
        1,
        dao.findAll().stream().filter(r -> "PIPELINE_EDIT".equals(r.kind)).count(),
        "exactly one PIPELINE_EDIT row — the second upsert did not insert a duplicate");
  }

  // ── forced TOCTOU race (fake DAO) ──────────────────────────────────────────

  @Test
  void upsertRecoversFromAConcurrentInsertRaceByRetryingTheUpdate() {
    RacingDao dao = new RacingDao(/* insertThrows= */ true, /* rowExistsOnRetry= */ true);

    // Must NOT throw: the lost INSERT race is recovered by the retry UPDATE.
    dao.upsert("RBAC_CHECK", 365);

    assertEquals(
        2, dao.updateCalls, "first UPDATE→0, then the recovery UPDATE after the failed INSERT");
    assertEquals(1, dao.insertCalls, "the INSERT was attempted exactly once");
  }

  @Test
  void upsertRethrowsWhenTheFailureWasNotALostRace() {
    // INSERT throws but the row still does not exist on retry (UPDATE→0) — e.g. a CHECK violation,
    // not a concurrency race. The original failure must propagate unchanged, not be swallowed.
    RacingDao dao = new RacingDao(/* insertThrows= */ true, /* rowExistsOnRetry= */ false);

    RuntimeException ex = assertThrows(RuntimeException.class, () -> dao.upsert("RBAC_CHECK", 0));
    assertSame(
        dao.thrownByInsert, ex, "the original INSERT failure is rethrown, not wrapped/masked");
    assertEquals(2, dao.updateCalls, "the recovery UPDATE was attempted before giving up");
  }

  /**
   * A hand-rolled {@link AuditRetentionPolicyDao} that drives {@code upsert}'s default-method
   * control flow without a database: UPDATE returns 0 until {@code rowExistsOnRetry} flips it to 1
   * after the INSERT attempt, and INSERT optionally throws to simulate the lost PK race.
   */
  private static final class RacingDao implements AuditRetentionPolicyDao {
    private final boolean insertThrows;
    private final boolean rowExistsOnRetry;
    final RuntimeException thrownByInsert = new RuntimeException("duplicate key value violates PK");
    int updateCalls;
    int insertCalls;
    private boolean inserted;

    RacingDao(boolean insertThrows, boolean rowExistsOnRetry) {
      this.insertThrows = insertThrows;
      this.rowExistsOnRetry = rowExistsOnRetry;
    }

    @Override
    public int update(String kind, int maxAgeDays) {
      updateCalls++;
      // First call (before any INSERT): row absent → 0. Retry after a "concurrent" insert: present.
      if (inserted || (updateCalls > 1 && rowExistsOnRetry)) {
        return 1;
      }
      return 0;
    }

    @Override
    public void insert(String kind, int maxAgeDays) {
      insertCalls++;
      if (insertThrows) {
        throw thrownByInsert;
      }
      inserted = true;
    }

    @Override
    public List<AuditRetentionPolicyRow> findAll() {
      return List.of();
    }

    @Override
    public Optional<AuditRetentionPolicyRow> findByKind(String kind) {
      return Optional.empty();
    }

    @Override
    public Handle getHandle() {
      throw new UnsupportedOperationException("not needed for the upsert seam test");
    }

    @Override
    public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) {
      throw new UnsupportedOperationException("not needed for the upsert seam test");
    }
  }
}
