package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.auth.Authz.TitanRole;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ScopedAuthz#roleFloorFromRealm} — the Quarkus realm-role → TitanRole
 * fallback floor consulted when neither the scoped {@code rbac_user_role} nor the flat {@code
 * user_roles} table carries a row for the caller (closes #1131).
 *
 * <p>Adversarial-first per the testing manifesto: every realm-role permutation that affects the
 * floor gets a named test; the rank table is exhaustive over {@link Authz.TitanRole}; null/empty
 * input is explicitly tested for default-deny.
 */
class ScopedAuthzRealmFallbackTest {

  // ── happy mappings ───────────────────────────────────────────────────────

  @Test
  void admin_realm_role_maps_to_admin() {
    assertEquals(TitanRole.ADMIN, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.ADMIN)));
  }

  @Test
  void edit_pipeline_maps_to_maintainer() {
    assertEquals(TitanRole.MAINTAINER, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.EDIT_PIPELINE)));
  }

  @Test
  void trigger_build_maps_to_developer() {
    assertEquals(TitanRole.DEVELOPER, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.TRIGGER_BUILD)));
  }

  @Test
  void replay_build_maps_to_developer() {
    assertEquals(TitanRole.DEVELOPER, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.REPLAY_BUILD)));
  }

  @Test
  void abort_build_maps_to_developer() {
    assertEquals(TitanRole.DEVELOPER, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.ABORT_BUILD)));
  }

  @Test
  void read_job_alone_maps_to_viewer() {
    assertEquals(TitanRole.VIEWER, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.READ_JOB)));
  }

  // ── dedicated operator roles (#1174) ─────────────────────────────────────

  /** The secrets operator is the admin of the credential store — admin floor for its surface. */
  @Test
  void manage_credentials_maps_to_admin() {
    assertEquals(TitanRole.ADMIN, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.MANAGE_CREDENTIALS)));
  }

  /** The worker operator is the admin of the worker pool — admin floor for its surface. */
  @Test
  void operate_worker_maps_to_admin() {
    assertEquals(TitanRole.ADMIN, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.OPERATE_WORKER)));
  }

  /** Approve-build clears the DEVELOPER gate on ApprovalsApi (#1174). */
  @Test
  void approve_build_maps_to_developer() {
    assertEquals(TitanRole.DEVELOPER, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.APPROVE_BUILD)));
  }

  /** Adversarial: a non-elevating dedicated role (READ_AUDIT) still floors to VIEWER. */
  @Test
  void read_audit_alone_maps_to_viewer() {
    assertEquals(TitanRole.VIEWER, ScopedAuthz.roleFloorFromRealm(Set.of(Roles.READ_AUDIT)));
  }

  // ── precedence (highest wins) ────────────────────────────────────────────

  @Test
  void admin_beats_lower_realm_roles() {
    assertEquals(
        TitanRole.ADMIN,
        ScopedAuthz.roleFloorFromRealm(
            Set.of(Roles.ADMIN, Roles.EDIT_PIPELINE, Roles.TRIGGER_BUILD, Roles.READ_JOB)));
  }

  @Test
  void edit_pipeline_beats_trigger_build() {
    assertEquals(
        TitanRole.MAINTAINER,
        ScopedAuthz.roleFloorFromRealm(Set.of(Roles.EDIT_PIPELINE, Roles.TRIGGER_BUILD)));
  }

  // ── adversarial: null / empty / unknown ──────────────────────────────────

  @Test
  void null_realm_set_returns_viewer() {
    assertEquals(TitanRole.VIEWER, ScopedAuthz.roleFloorFromRealm(null));
  }

  @Test
  void empty_realm_set_returns_viewer() {
    assertEquals(TitanRole.VIEWER, ScopedAuthz.roleFloorFromRealm(Set.of()));
  }

  /** Unknown realm role names default-deny — no implicit elevation. */
  @Test
  void unknown_realm_role_returns_viewer() {
    assertEquals(
        TitanRole.VIEWER, ScopedAuthz.roleFloorFromRealm(Set.of("SOME_FUTURE_ROLE", "GUEST")));
  }

  // ── canonicalRealmRoles: downward closure (inverse of roleFloorFromRealm) ──────

  @Test
  void viewer_closure_is_read_job_only() {
    assertEquals(Set.of(Roles.READ_JOB), ScopedAuthz.canonicalRealmRoles(TitanRole.VIEWER));
  }

  @Test
  void developer_closure_includes_build_roles_and_read() {
    assertEquals(
        Set.of(
            Roles.READ_JOB,
            Roles.TRIGGER_BUILD,
            Roles.REPLAY_BUILD,
            Roles.ABORT_BUILD,
            Roles.APPROVE_BUILD),
        ScopedAuthz.canonicalRealmRoles(TitanRole.DEVELOPER));
  }

  @Test
  void maintainer_closure_adds_edit_pipeline_over_developer() {
    assertEquals(
        Set.of(
            Roles.READ_JOB,
            Roles.TRIGGER_BUILD,
            Roles.REPLAY_BUILD,
            Roles.ABORT_BUILD,
            Roles.APPROVE_BUILD,
            Roles.EDIT_PIPELINE),
        ScopedAuthz.canonicalRealmRoles(TitanRole.MAINTAINER));
  }

  @Test
  void admin_closure_adds_admin_over_maintainer() {
    assertEquals(
        Set.of(
            Roles.READ_JOB,
            Roles.TRIGGER_BUILD,
            Roles.REPLAY_BUILD,
            Roles.ABORT_BUILD,
            Roles.APPROVE_BUILD,
            Roles.EDIT_PIPELINE,
            Roles.ADMIN),
        ScopedAuthz.canonicalRealmRoles(TitanRole.ADMIN));
  }

  /**
   * Round-trip invariant: every realm role in a granted role's closure must floor back to a
   * TitanRole no higher than the grant — the closure never over-grants beyond its own tier.
   */
  @Test
  void closure_never_floors_above_the_grant() {
    for (TitanRole granted : TitanRole.values()) {
      for (String realmRole : ScopedAuthz.canonicalRealmRoles(granted)) {
        TitanRole floor = ScopedAuthz.roleFloorFromRealm(Set.of(realmRole));
        assertTrue(
            ScopedAuthz.satisfies(granted, floor),
            "closure of " + granted + " contains " + realmRole + " which floors to " + floor);
      }
    }
  }
}
