package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.auth.Authz.TitanRole;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the pure decision + parsing surface of {@link ScopedAuthz} — closes #1131.
 *
 * <p>The DB-backed {@code requires(...)} happy path is covered by the Quarkus IT sibling. Here we
 * exercise the static rank table, the role-hierarchy "highest wins" comparison, and the {@code
 * <org>/<repo>} parent extraction. Adversarial coverage on every input slot per the testing
 * manifesto: lone-slash, leading-slash, trailing-slash, empty.
 */
class ScopedAuthzTest {

  // ── rank / satisfies ─────────────────────────────────────────────────────

  @Test
  void admin_satisfies_every_required_role() {
    for (TitanRole r : TitanRole.values()) {
      assertTrue(ScopedAuthz.satisfies(TitanRole.ADMIN, r), "ADMIN must satisfy " + r);
    }
  }

  @Test
  void viewer_only_satisfies_viewer() {
    assertTrue(ScopedAuthz.satisfies(TitanRole.VIEWER, TitanRole.VIEWER));
    assertFalse(ScopedAuthz.satisfies(TitanRole.VIEWER, TitanRole.DEVELOPER));
    assertFalse(ScopedAuthz.satisfies(TitanRole.VIEWER, TitanRole.MAINTAINER));
    assertFalse(ScopedAuthz.satisfies(TitanRole.VIEWER, TitanRole.ADMIN));
  }

  @Test
  void developer_satisfies_developer_and_viewer_only() {
    assertTrue(ScopedAuthz.satisfies(TitanRole.DEVELOPER, TitanRole.DEVELOPER));
    assertTrue(ScopedAuthz.satisfies(TitanRole.DEVELOPER, TitanRole.VIEWER));
    assertFalse(ScopedAuthz.satisfies(TitanRole.DEVELOPER, TitanRole.MAINTAINER));
    assertFalse(ScopedAuthz.satisfies(TitanRole.DEVELOPER, TitanRole.ADMIN));
  }

  @Test
  void maintainer_satisfies_everything_below_admin() {
    assertTrue(ScopedAuthz.satisfies(TitanRole.MAINTAINER, TitanRole.MAINTAINER));
    assertTrue(ScopedAuthz.satisfies(TitanRole.MAINTAINER, TitanRole.DEVELOPER));
    assertTrue(ScopedAuthz.satisfies(TitanRole.MAINTAINER, TitanRole.VIEWER));
    assertFalse(ScopedAuthz.satisfies(TitanRole.MAINTAINER, TitanRole.ADMIN));
  }

  // ── parentOrgFromRepo ────────────────────────────────────────────────────

  @Test
  void parentOrgFromRepo_happy() {
    assertEquals("acme", ScopedAuthz.parentOrgFromRepo("acme/widgets"));
  }

  @Test
  void parentOrgFromRepo_nested_repo_keeps_org() {
    // The first segment is always the org; '/' inside the repo name is treated as part of the repo
    // id by the upstream resolver — we only care about the org prefix here.
    assertEquals("acme", ScopedAuthz.parentOrgFromRepo("acme/group/widgets"));
  }

  /** Adversarial — lone slash returns null (no org). */
  @Test
  void parentOrgFromRepo_loneSlash_returnsNull() {
    assertNull(ScopedAuthz.parentOrgFromRepo("/"));
  }

  /** Adversarial — leading slash returns null (no org). */
  @Test
  void parentOrgFromRepo_leadingSlash_returnsNull() {
    assertNull(ScopedAuthz.parentOrgFromRepo("/widgets"));
  }

  /** Adversarial — trailing slash returns null (no repo half). */
  @Test
  void parentOrgFromRepo_trailingSlash_returnsNull() {
    assertNull(ScopedAuthz.parentOrgFromRepo("acme/"));
  }

  /** Adversarial — no slash at all returns null. */
  @Test
  void parentOrgFromRepo_noSlash_returnsNull() {
    assertNull(ScopedAuthz.parentOrgFromRepo("acme"));
  }

  /** Adversarial — empty string returns null. */
  @Test
  void parentOrgFromRepo_empty_returnsNull() {
    assertNull(ScopedAuthz.parentOrgFromRepo(""));
  }
}
