package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PatScopes} — the core invariant under test is the third assertion in #500:
 * the effective role set of a PAT is the INTERSECTION (never the union) of (creator's roles) ∩ (PAT
 * scopes), and a creator can never expand their permissions by minting a scoped token.
 */
class PatScopesTest {

  // ── validate ────────────────────────────────────────────────────────────────

  @Test
  void validate_nullOrEmpty_returnsNullMeaningInheritAll() {
    assertNull(PatScopes.validate(null));
    assertNull(PatScopes.validate(List.of()));
  }

  @Test
  void validate_knownScopes_returnsNormalisedList() {
    List<String> out = PatScopes.validate(List.of("READ_JOB", "TRIGGER_BUILD"));
    assertNotNull(out);
    assertEquals(List.of("READ_JOB", "TRIGGER_BUILD"), out);
  }

  @Test
  void validate_unknownScope_throws() {
    PatScopes.InvalidPatScopeException ex =
        assertThrows(
            PatScopes.InvalidPatScopeException.class,
            () -> PatScopes.validate(List.of("READ_JOB", "DELETE_EVERYTHING")));
    assertTrue(ex.getMessage().contains("DELETE_EVERYTHING"), ex.getMessage());
  }

  @Test
  void validate_adminIsNeverGrantable_throws() {
    // Critical: a PAT must never be able to mint ADMIN, even via the wire field.
    assertThrows(
        PatScopes.InvalidPatScopeException.class, () -> PatScopes.validate(List.of("ADMIN")));
  }

  @Test
  void validate_blankEntry_throws() {
    assertThrows(
        PatScopes.InvalidPatScopeException.class,
        () -> PatScopes.validate(java.util.Arrays.asList("READ_JOB", "  ")));
  }

  @Test
  void validate_nullEntry_throws() {
    assertThrows(
        PatScopes.InvalidPatScopeException.class,
        () -> PatScopes.validate(java.util.Arrays.asList("READ_JOB", null)));
  }

  @Test
  void validate_dedupesAndTrims() {
    List<String> out = PatScopes.validate(List.of("READ_JOB", " READ_JOB ", "TRIGGER_BUILD"));
    assertEquals(List.of("READ_JOB", "TRIGGER_BUILD"), out);
  }

  // ── toJson / parseOrNull round-trip ─────────────────────────────────────────

  @Test
  void jsonRoundTrip_preservesOrderAndContent() {
    List<String> in = PatScopes.validate(List.of("READ_JOB", "TRIGGER_BUILD"));
    String json = PatScopes.toJson(in);
    assertNotNull(json);
    List<String> out = PatScopes.parseOrNull(json);
    assertEquals(in, out);
  }

  @Test
  void toJson_null_returnsNull() {
    assertNull(PatScopes.toJson(null));
  }

  @Test
  void parseOrNull_nullOrBlank_returnsNull() {
    assertNull(PatScopes.parseOrNull(null));
    assertNull(PatScopes.parseOrNull(""));
    assertNull(PatScopes.parseOrNull("   "));
  }

  @Test
  void parseOrNull_malformedJson_returnsNullFailOpen() {
    // Hostile / corrupted DB content must not crash auth — we degrade to "no scope restriction".
    // The bearer-auth path still caps at PAT_ROLES, so this CANNOT escalate above the legacy
    // ceiling. Verified by the intersection_neverWidens_test below.
    assertNull(PatScopes.parseOrNull("{ not json"));
    assertNull(PatScopes.parseOrNull("\"not-an-array\""));
  }

  @Test
  void parseOrNull_unknownEntriesAreStrippedSilently() {
    // A row hand-edited to include a bogus scope should round-trip as if those entries did not
    // exist. The intersection step then ignores them naturally.
    List<String> out = PatScopes.parseOrNull("[\"READ_JOB\",\"BOGUS\"]");
    assertEquals(List.of("READ_JOB"), out);
  }

  // ── intersect — the security invariant ──────────────────────────────────────

  @Test
  void intersect_nullScopes_returnsGrantedRolesUnchanged() {
    // Legacy PAT: scopes_json IS NULL → behave as #477 did (inherit all granted roles).
    Set<String> granted = Set.of(Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.EDIT_PIPELINE);
    Set<String> out = PatScopes.intersect(granted, null);
    assertSame(granted, out, "null scopes must be a no-op, returning the input set by reference");
  }

  @Test
  void intersect_returnsIntersection_notUnion() {
    // Granted: READ_JOB only. Scopes ask for READ_JOB + TRIGGER_BUILD.
    // Effective MUST be {READ_JOB} (intersection), never {READ_JOB, TRIGGER_BUILD} (union).
    Set<String> granted = Set.of(Roles.READ_JOB);
    Set<String> out = PatScopes.intersect(granted, List.of(Roles.READ_JOB, Roles.TRIGGER_BUILD));
    assertEquals(Set.of(Roles.READ_JOB), out);
  }

  @Test
  void intersect_scopeNotInGranted_isDropped() {
    Set<String> granted = Set.of(Roles.READ_JOB, Roles.EDIT_PIPELINE);
    // PAT asks for TRIGGER_BUILD which the creator does NOT have — must NOT be granted.
    Set<String> out = PatScopes.intersect(granted, List.of(Roles.TRIGGER_BUILD, Roles.READ_JOB));
    assertEquals(Set.of(Roles.READ_JOB), out);
  }

  @Test
  void intersect_emptyIntersection_returnsEmptySet() {
    Set<String> granted = Set.of(Roles.READ_JOB);
    Set<String> out = PatScopes.intersect(granted, List.of(Roles.APPROVE_GATE));
    assertTrue(out.isEmpty(), "no overlap → no roles, the PAT is effectively powerless");
  }

  @Test
  void intersect_neverWidens_creatorCannotEscalate() {
    // Property-style: for ANY granted set and ANY scope list, intersect's output is a subset of
    // granted. This is the invariant that prevents privilege escalation via PAT scopes.
    Set<String> granted = new LinkedHashSet<>();
    granted.add(Roles.READ_JOB);
    granted.add(Roles.TRIGGER_BUILD);
    // Scopes contain a role the creator does NOT have:
    List<String> scopes = List.of(Roles.READ_JOB, Roles.EDIT_PIPELINE, Roles.APPROVE_GATE);
    Set<String> out = PatScopes.intersect(granted, scopes);
    assertTrue(granted.containsAll(out), "intersect must never produce a role outside `granted`");
    assertEquals(Set.of(Roles.READ_JOB), out);
  }
}
