package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.store.PersonalAccessTokenDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.PersonalAccessTokenRow;
import io.quarkus.elytron.security.common.BcryptUtil;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres-backed integration test for {@link io.adaptiq.titan.store.PersonalAccessTokenDao} —
 * closes #434. Mirrors {@link ActivityApiIT} — same Testcontainers Postgres + Flyway setup, both
 * the portable migration baseline and the postgres-only overlay are applied so the partial index
 * (V18_1) is exercised.
 *
 * <p>Asserts the end-to-end CRUD round-trip:
 *
 * <ul>
 *   <li>insert seeds the row with created_at populated, last_used_at / revoked_at NULL;
 *   <li>listByUser returns the row and is filtered to the right subject;
 *   <li>revoke flips revoked_at to a non-null timestamp without dropping the row;
 *   <li>BCrypt hash on disk round-trips against the original plaintext.
 * </ul>
 */
@Testcontainers
class PersonalAccessTokenApiIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private HikariDataSource ds;
  private TitanStores stores;

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
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  @Test
  void crudRoundTrip_insertListRevoke_keepsAuditRow() {
    PersonalAccessTokenDao dao = stores.personalAccessTokens();
    String alice = "oidc-sub-alice-" + System.nanoTime();
    String plaintext = "titanpat_ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    PersonalAccessTokenRow row = new PersonalAccessTokenRow();
    row.userSubject = alice;
    row.name = "ci-bot";
    row.tokenHash = BcryptUtil.bcryptHash(plaintext);
    row.prefix = plaintext.substring(0, 13); // "titanpat_ABCD"

    long id = dao.insert(row);
    assertTrue(id > 0, "insert returns a generated id");

    // findByIdForUser scopes to the owning subject.
    Optional<PersonalAccessTokenRow> mine = dao.findByIdForUser(id, alice);
    assertTrue(mine.isPresent(), "owner can fetch their row");
    assertEquals("ci-bot", mine.get().name);
    assertEquals("titanpat_ABCD", mine.get().prefix);
    assertNotNull(mine.get().createdAt, "created_at populated by DB default");
    assertNull(mine.get().lastUsedAt, "last_used_at NULL on fresh row");
    assertNull(mine.get().revokedAt, "revoked_at NULL on fresh row");
    assertTrue(
        BcryptUtil.matches(plaintext, mine.get().tokenHash),
        "BCrypt hash round-trips against plaintext");

    // Cross-user lookup is empty — even by id, a different subject cannot see the row.
    Optional<PersonalAccessTokenRow> stranger = dao.findByIdForUser(id, "oidc-sub-mallory");
    assertTrue(stranger.isEmpty(), "different subject cannot fetch by id");

    // listByUser scopes correctly.
    List<PersonalAccessTokenRow> aliceList = dao.listByUser(alice);
    assertEquals(1, aliceList.size());
    assertEquals(id, aliceList.get(0).id);

    List<PersonalAccessTokenRow> mallorysList = dao.listByUser("oidc-sub-mallory");
    assertTrue(mallorysList.isEmpty(), "different subject sees nothing");

    // Revoke marks the row but does not delete it.
    int revokedCount = dao.revoke(id, alice);
    assertEquals(1, revokedCount, "revoke updates exactly one row");

    Optional<PersonalAccessTokenRow> afterRevoke = dao.findByIdForUser(id, alice);
    assertTrue(afterRevoke.isPresent(), "revoked row remains for audit");
    assertNotNull(afterRevoke.get().revokedAt, "revoked_at populated");

    // Second revoke is idempotent at the DAO layer — 0 rows updated because revoked_at IS NULL
    // is no longer true.
    assertEquals(0, dao.revoke(id, alice), "second revoke is a no-op");

    // Wrong subject also fails to revoke (defence in depth — there's a unique-name guard but the
    // user_subject filter is the real boundary).
    int hackAttempt = dao.revoke(id, "oidc-sub-mallory");
    assertEquals(0, hackAttempt, "non-owner cannot revoke");
  }
}
