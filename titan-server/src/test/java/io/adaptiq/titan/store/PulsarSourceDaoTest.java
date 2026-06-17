package io.adaptiq.titan.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.db.TitanDataException;
import io.adaptiq.titan.store.rows.PulsarSourceRow;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Direct DAO tests for {@link PulsarSourceDao} (#1283).
 *
 * <p>Backed by the same H2 schema-loader the API tests use ({@code FakeTitanStores}), reached via
 * reflection because {@code FakeTitanStores} lives in the API test source set — mirrors {@link
 * ArtifactDaoTest}. Each test gets a fresh, isolated database.
 */
class PulsarSourceDaoTest {

  private TitanStores stores;

  @BeforeEach
  void setUp() throws Exception {
    Class<?> fake = Class.forName("io.adaptiq.titan.api.FakeTitanStores");
    Method create = fake.getDeclaredMethod("create");
    create.setAccessible(true);
    stores = (TitanStores) create.invoke(null);
  }

  @Test
  void listAll_emptyByDefault() {
    assertTrue(stores.pulsarSources().listAll().isEmpty());
  }

  @Test
  void insert_thenFindRoundTrip() {
    long id = stores.pulsarSources().insert("https://pulsar.example.com", "prod", 7);

    Optional<PulsarSourceRow> byId = stores.pulsarSources().findById(id);
    assertTrue(byId.isPresent());
    PulsarSourceRow row = byId.get();
    assertEquals(id, row.id);
    assertEquals("https://pulsar.example.com", row.nodeUrl);
    assertEquals("prod", row.nodeName);
    assertEquals(Integer.valueOf(7), row.repoCount);
    assertNotNull(row.lastPolledAt, "insert stamps last_polled_at");
    assertNotNull(row.createdAt);
    assertNotNull(row.updatedAt);

    Optional<PulsarSourceRow> byUrl =
        stores.pulsarSources().findByNodeUrl("https://pulsar.example.com");
    assertTrue(byUrl.isPresent());
    assertEquals(id, byUrl.get().id);
  }

  @Test
  void insert_nullableNodeNameAndRepoCount() {
    long id = stores.pulsarSources().insert("https://p2.example.com", null, null);
    PulsarSourceRow row = stores.pulsarSources().findById(id).orElseThrow();
    assertNull(row.nodeName);
    assertNull(row.repoCount);
  }

  @Test
  void findById_unknownReturnsEmpty() {
    assertTrue(stores.pulsarSources().findById(424242L).isEmpty());
  }

  @Test
  void findByNodeUrl_unknownReturnsEmpty() {
    assertTrue(stores.pulsarSources().findByNodeUrl("https://nope.example.com").isEmpty());
  }

  @Test
  void listAll_returnsEveryInsertedRow() {
    stores.pulsarSources().insert("https://a.example.com", "a", 1);
    stores.pulsarSources().insert("https://b.example.com", "b", 2);
    stores.pulsarSources().insert("https://c.example.com", null, null);

    List<PulsarSourceRow> all = stores.pulsarSources().listAll();
    assertEquals(3, all.size());
    // Ordered by id ascending — insert order.
    assertEquals("https://a.example.com", all.get(0).nodeUrl);
    assertEquals("https://c.example.com", all.get(2).nodeUrl);
  }

  @Test
  void duplicateNodeUrl_violatesUniqueConstraint() {
    stores.pulsarSources().insert("https://dup.example.com", "first", 1);
    // The unique constraint surfaces as a TitanDataException via the translating proxy.
    assertThrows(
        TitanDataException.class,
        () -> stores.pulsarSources().insert("https://dup.example.com", "second", 2));
  }

  @Test
  void updateSyncResult_refreshesRepoCountAndPolledAt() throws Exception {
    long id = stores.pulsarSources().insert("https://sync.example.com", "s", 3);
    PulsarSourceRow before = stores.pulsarSources().findById(id).orElseThrow();

    // Sleep a beat so the CURRENT_TIMESTAMP advance is observable.
    Thread.sleep(5);
    int updated = stores.pulsarSources().updateSyncResult(id, 11);
    assertEquals(1, updated);

    PulsarSourceRow after = stores.pulsarSources().findById(id).orElseThrow();
    assertEquals(Integer.valueOf(11), after.repoCount);
    assertNotNull(after.lastPolledAt);
    assertFalse(
        after.updatedAt.isBefore(before.updatedAt), "updated_at must not move backwards on sync");
  }

  @Test
  void updateSyncResult_unknownIdReturnsZero() {
    assertEquals(0, stores.pulsarSources().updateSyncResult(999999L, 5));
  }
}
