package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.auth.Authz.TitanRole;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the pure policy + parsing surface of {@link Authz} — closes #1121.
 *
 * <p>The DB-backed {@code requires(...)} path is covered by the IT siblings (see {@code
 * BuildRerunRbacIT} / {@code PipelineEditRbacIT}). Here we exercise the static decision table for
 * every (role × action) combination, the default-deny case, and the role-string parser's robustness
 * against null / blank / unknown values — the rule from the testing manifesto being "every
 * assumption about input gets one adversarial test for the false case".
 */
class AuthzTest {

  // ── policy matrix ──────────────────────────────────────────────────────────

  @Test
  void admin_allowed_for_both_actions() {
    Set<TitanRole> roles = EnumSet.of(TitanRole.ADMIN);
    assertTrue(Authz.isAllowed(Action.BUILD_RERUN, roles));
    assertTrue(Authz.isAllowed(Action.PIPELINE_EDIT, roles));
  }

  @Test
  void maintainer_allowed_for_both_actions() {
    Set<TitanRole> roles = EnumSet.of(TitanRole.MAINTAINER);
    assertTrue(Authz.isAllowed(Action.BUILD_RERUN, roles));
    assertTrue(Authz.isAllowed(Action.PIPELINE_EDIT, roles));
  }

  @Test
  void developer_denied_for_both_actions() {
    Set<TitanRole> roles = EnumSet.of(TitanRole.DEVELOPER);
    assertFalse(Authz.isAllowed(Action.BUILD_RERUN, roles));
    assertFalse(Authz.isAllowed(Action.PIPELINE_EDIT, roles));
  }

  @Test
  void viewer_denied_for_both_actions() {
    Set<TitanRole> roles = EnumSet.of(TitanRole.VIEWER);
    assertFalse(Authz.isAllowed(Action.BUILD_RERUN, roles));
    assertFalse(Authz.isAllowed(Action.PIPELINE_EDIT, roles));
  }

  /** Adversarial — default-deny: no row in {@code user_roles} → empty set → deny. */
  @Test
  void empty_role_set_denied_for_both_actions() {
    Set<TitanRole> roles = Set.of();
    assertFalse(Authz.isAllowed(Action.BUILD_RERUN, roles));
    assertFalse(Authz.isAllowed(Action.PIPELINE_EDIT, roles));
  }

  /** Adversarial — multi-role: ADMIN + DEVELOPER → ADMIN allows. */
  @Test
  void multiple_roles_admin_wins_over_developer() {
    Set<TitanRole> roles = EnumSet.of(TitanRole.ADMIN, TitanRole.DEVELOPER);
    assertTrue(Authz.isAllowed(Action.BUILD_RERUN, roles));
    assertTrue(Authz.isAllowed(Action.PIPELINE_EDIT, roles));
  }

  /** Adversarial — DEVELOPER + VIEWER alone does NOT promote to MAINTAINER. */
  @Test
  void developer_plus_viewer_still_denied() {
    Set<TitanRole> roles = EnumSet.of(TitanRole.DEVELOPER, TitanRole.VIEWER);
    assertFalse(Authz.isAllowed(Action.BUILD_RERUN, roles));
    assertFalse(Authz.isAllowed(Action.PIPELINE_EDIT, roles));
  }

  // ── parseRoles ─────────────────────────────────────────────────────────────

  @Test
  void parseRoles_happy() {
    Set<TitanRole> out = Authz.parseRoles(List.of("ADMIN", "DEVELOPER"));
    assertEquals(EnumSet.of(TitanRole.ADMIN, TitanRole.DEVELOPER), out);
  }

  @Test
  void parseRoles_caseInsensitive_andTrimmed() {
    Set<TitanRole> out = Authz.parseRoles(List.of(" admin ", "Maintainer"));
    assertEquals(EnumSet.of(TitanRole.ADMIN, TitanRole.MAINTAINER), out);
  }

  /** Adversarial — null and unknown values must not throw; they're silently dropped. */
  @Test
  void parseRoles_nullAndUnknown_dropped() {
    Set<TitanRole> out =
        Authz.parseRoles(
            java.util.Arrays.asList(null, "ADMIN", "ROOT", "DELETE_EVERYTHING", "viewer"));
    assertEquals(EnumSet.of(TitanRole.ADMIN, TitanRole.VIEWER), out);
  }

  /** Adversarial — all-garbage collapses to empty set → default-deny. */
  @Test
  void parseRoles_allUnknown_isEmptyAndDeniedByPolicy() {
    Set<TitanRole> out = Authz.parseRoles(List.of("ROOT", "SUPERADMIN"));
    assertTrue(out.isEmpty());
    assertFalse(Authz.isAllowed(Action.BUILD_RERUN, out));
    assertFalse(Authz.isAllowed(Action.PIPELINE_EDIT, out));
  }

  // ── Action → AuditAction mapping ───────────────────────────────────────────

  @Test
  void auditActionFor_is_total_over_action_enum() {
    for (Action a : Action.values()) {
      assertNotNull(Authz.auditActionFor(a), "no AuditAction for " + a);
    }
  }

  // ── Resource.auditTag ──────────────────────────────────────────────────────

  @Test
  void jobResource_auditTag_isStable() {
    assertEquals("job:42", new Resource.JobResource(42L).auditTag());
  }
}
