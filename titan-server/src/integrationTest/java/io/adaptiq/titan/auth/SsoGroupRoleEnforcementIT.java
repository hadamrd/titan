package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.adaptiq.titan.auth.Authz.TitanRole;
import io.adaptiq.titan.store.TitanStores;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end RBAC enforcement for the SSO group → role mapping (closes #1235).
 *
 * <p>Drives the real chain against a real Postgres Testcontainer: a seeded {@code
 * group_role_mapping} row → {@link GroupRoleAugmentor} (reads the OIDC {@code groups} claim,
 * resolves via {@link GroupRoleResolver} + the real DAO) → the augmented {@link SecurityIdentity} →
 * the {@link ScopedAuthz} decision a {@code @RequiresRole(MAINTAINER, ORG, "global")} gate makes.
 * No HTTP layer: {@code @TestSecurity} cannot carry a list-valued OIDC claim, so we exercise the
 * augmentor + decision seam directly — the exact value {@link ScopedAuthz#requires} bases its
 * 403/allow on.
 *
 * <p>Acceptance criteria proven:
 *
 * <ul>
 *   <li>AC2 — {@code /release-eng → MAINTAINER (org global)} grants the MAINTAINER gate with NO row
 *       in {@code rbac_user_role} or {@code user_roles}.
 *   <li>AC3 — absent / malformed groups claim → not elevated, no exception (login succeeds at the
 *       VIEWER floor).
 *   <li>AC4 — deleting the mapping row revokes the grant on the very next augmentation (no
 *       restart).
 * </ul>
 */
@Testcontainers
class SsoGroupRoleEnforcementIT {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  private static final String ORG = "global";
  private static final String GROUP = "/release-eng";
  private static final String USER = "alice";

  private HikariDataSource ds;
  private TitanStores stores;
  private GroupRoleAugmentor augmentor;
  private ScopedAuthz scopedAuthz;

  @BeforeEach
  void setUp() throws Exception {
    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setDriverClassName("org.postgresql.Driver");
    cfg.setMaximumPoolSize(4);
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
    augmentor = new GroupRoleAugmentor(stores.groupRoleMapping());
    // audit is unused by effectiveRoleWithRealmFloor — the decision seam under test never writes.
    scopedAuthz = new ScopedAuthz(stores, null);
  }

  @AfterEach
  void tearDown() {
    if (ds != null) {
      ds.close();
    }
  }

  // ── AC2: group mapping alone grants the gated role, no DB role seed ─────────

  @Test
  void release_eng_group_grants_maintainer_with_no_db_role_seed() {
    long id = stores.groupRoleMapping().insert(ORG, GROUP, "MAINTAINER");
    assertTrue(id > 0, "mapping seeded");
    assertNoScopedOrFlatRoleSeed(); // the ONLY grant is the group mapping

    SecurityIdentity augmented = augmentor.augmentBlocking(identityInGroups(List.of(GROUP)));

    assertTrue(
        augmented.getRoles().contains(Roles.EDIT_PIPELINE),
        "augmentor injected MAINTAINER's canonical realm role; got " + augmented.getRoles());
    // Under-grant guard: the MAINTAINER closure must ALSO carry the lower-tier realm roles so the
    // caller clears the coarse @RolesAllowed({READ_JOB,...}) gates on every job/build/node/log read
    // — Quarkus @RolesAllowed is literal membership with no hierarchy.
    assertTrue(
        augmented.getRoles().contains(Roles.READ_JOB)
            && augmented.getRoles().contains(Roles.TRIGGER_BUILD),
        "group mapping alone must clear read + build @RolesAllowed gates; got "
            + augmented.getRoles());

    TitanRole effective =
        scopedAuthz.effectiveRoleWithRealmFloor(new AuthContext(augmented), ScopeKind.ORG, ORG);
    assertEquals(TitanRole.MAINTAINER, effective, "group-derived effective role on org " + ORG);
    assertTrue(
        ScopedAuthz.satisfies(effective, TitanRole.MAINTAINER),
        "MAINTAINER-gated endpoint would ALLOW (HTTP 200, not 403)");
  }

  // ── AC3: no / malformed claim → not elevated, no crash ──────────────────────

  @Test
  void no_groups_claim_is_not_elevated_and_login_succeeds() {
    stores.groupRoleMapping().insert(ORG, GROUP, "MAINTAINER");

    SecurityIdentity augmented =
        augmentor.augmentBlocking(
            QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(USER)).build());

    assertFalse(augmented.getRoles().contains(Roles.EDIT_PIPELINE));
    TitanRole effective =
        scopedAuthz.effectiveRoleWithRealmFloor(new AuthContext(augmented), ScopeKind.ORG, ORG);
    assertEquals(TitanRole.VIEWER, effective, "no group → VIEWER floor");
    assertFalse(
        ScopedAuthz.satisfies(effective, TitanRole.MAINTAINER),
        "MAINTAINER-gated endpoint would DENY (HTTP 403)");
  }

  @Test
  void malformed_non_list_claim_does_not_crash_and_does_not_elevate() {
    stores.groupRoleMapping().insert(ORG, GROUP, "MAINTAINER");
    SecurityIdentity in =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(USER))
            .addAttribute(GroupRoleAugmentor.GROUPS_CLAIM, Integer.valueOf(7))
            .build();

    SecurityIdentity augmented = augmentor.augmentBlocking(in); // must not throw

    assertFalse(augmented.getRoles().contains(Roles.EDIT_PIPELINE));
  }

  // ── AC4: removing the row revokes on the next request, no restart ───────────

  @Test
  void deleting_mapping_revokes_grant_on_next_augmentation() {
    long id = stores.groupRoleMapping().insert(ORG, GROUP, "MAINTAINER");
    assertTrue(
        augmentor
            .augmentBlocking(identityInGroups(List.of(GROUP)))
            .getRoles()
            .contains(Roles.EDIT_PIPELINE),
        "granted while the row exists");

    int deleted = stores.groupRoleMapping().delete(id);
    assertEquals(1, deleted);

    SecurityIdentity after = augmentor.augmentBlocking(identityInGroups(List.of(GROUP)));
    assertFalse(
        after.getRoles().contains(Roles.EDIT_PIPELINE),
        "revoked on the very next augmentation — no restart");
    assertEquals(
        TitanRole.VIEWER,
        scopedAuthz.effectiveRoleWithRealmFloor(new AuthContext(after), ScopeKind.ORG, ORG));
  }

  // ── helpers ─────────────────────────────────────────────────────────────────

  private static SecurityIdentity identityInGroups(List<String> groups) {
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new QuarkusPrincipal(USER))
        .addAttribute(GroupRoleAugmentor.GROUPS_CLAIM, groups)
        .build();
  }

  /**
   * Assert the subject has zero scoped + zero flat RBAC rows — the group mapping is the only path.
   */
  private void assertNoScopedOrFlatRoleSeed() {
    assertTrue(
        stores.rbacUserRoles().findByUserAndScope(USER, ScopeKind.ORG.name(), ORG).isEmpty(),
        "no rbac_user_role seed");
    assertTrue(stores.userRoles().findByUserId(USER).isEmpty(), "no flat user_roles seed");
  }
}
