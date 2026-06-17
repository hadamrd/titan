package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.rows.GroupRoleMappingRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-function coverage for {@link GroupRoleResolver} — closes #1136.
 *
 * <p>Hits the cases called out in the ticket's "Test matrix":
 *
 * <ul>
 *   <li>Exact match → role assigned.
 *   <li>No mapping → empty result.
 *   <li>Multiple roles same org → all roles in the set; highest-role-wins projection picks ADMIN.
 *   <li>Unknown role string in DB → skipped, not 500.
 *   <li>Malformed claim (string instead of list) → coerce, never crash.
 *   <li>Null / wrong-typed claim → empty result, no exception.
 * </ul>
 */
class GroupRoleResolverTest {

  // ── claim coercion ─────────────────────────────────────────────────────────

  @Test
  void coerce_null_claim_is_empty() {
    assertTrue(GroupRoleResolver.coerceClaim(null).isEmpty());
  }

  @Test
  void coerce_list_claim_passes_through() {
    List<String> in = List.of("/acme/admins", "/acme/devs");
    assertEquals(in, GroupRoleResolver.coerceClaim(in));
  }

  @Test
  void coerce_list_drops_null_and_blank_entries() {
    List<String> in = new ArrayList<>();
    in.add("/a");
    in.add(null);
    in.add("");
    in.add("  ");
    in.add(" /b ");
    assertEquals(List.of("/a", "/b"), GroupRoleResolver.coerceClaim(in));
  }

  @Test
  void coerce_single_string_becomes_one_element_list() {
    // Adversarial: spec acceptance criterion (b) — malformed claim (string instead of array).
    // Login must NOT 500; we coerce to a one-element list.
    assertEquals(List.of("/acme/admins"), GroupRoleResolver.coerceClaim("/acme/admins"));
  }

  @Test
  void coerce_csv_string_is_split() {
    assertEquals(
        List.of("/acme/admins", "/acme/devs"),
        GroupRoleResolver.coerceClaim("/acme/admins, /acme/devs"));
  }

  @Test
  void coerce_unknown_type_is_empty_not_crash() {
    // E.g. a number landed in the claim — never 500.
    assertEquals(List.of(), GroupRoleResolver.coerceClaim(42));
  }

  // ── foldRows: aggregation ──────────────────────────────────────────────────

  @Test
  void foldRows_empty_input_is_empty_output() {
    assertTrue(GroupRoleResolver.foldRows(List.of()).isEmpty());
  }

  @Test
  void foldRows_assigns_role_per_org() {
    GroupRoleMappingRow a = row(1, "acme", "/acme/admins", "ADMIN");
    GroupRoleMappingRow b = row(2, "globex", "/globex/devs", "DEVELOPER");
    Map<String, Set<String>> out = GroupRoleResolver.foldRows(List.of(a, b));
    assertEquals(Set.of("ADMIN"), out.get("acme"));
    assertEquals(Set.of("DEVELOPER"), out.get("globex"));
  }

  @Test
  void foldRows_multiple_groups_same_org_keeps_union() {
    GroupRoleMappingRow admin = row(1, "acme", "/acme/admins", "ADMIN");
    GroupRoleMappingRow dev = row(2, "acme", "/acme/devs", "DEVELOPER");
    Map<String, Set<String>> out = GroupRoleResolver.foldRows(List.of(admin, dev));
    assertEquals(Set.of("ADMIN", "DEVELOPER"), out.get("acme"));
  }

  @Test
  void foldRows_unknown_role_in_db_is_skipped() {
    // Adversarial: spec acceptance criterion (c) — mapping row points at a role that's been
    // deleted from the enum. Must skip, never 500.
    GroupRoleMappingRow good = row(1, "acme", "/acme/devs", "DEVELOPER");
    GroupRoleMappingRow bad = row(2, "acme", "/acme/gone", "SUPERUSER_OBSOLETE");
    Map<String, Set<String>> out = GroupRoleResolver.foldRows(List.of(good, bad));
    assertEquals(Set.of("DEVELOPER"), out.get("acme"));
  }

  @Test
  void foldRows_role_normalised_to_uppercase() {
    GroupRoleMappingRow row = row(1, "acme", "/acme/admins", "admin"); // legacy lowercase row
    Map<String, Set<String>> out = GroupRoleResolver.foldRows(List.of(row));
    assertEquals(Set.of("ADMIN"), out.get("acme"));
  }

  @Test
  void foldRows_null_field_rows_are_skipped() {
    GroupRoleMappingRow nullOrg = row(1, null, "/acme/admins", "ADMIN");
    GroupRoleMappingRow nullRole = row(2, "acme", "/acme/admins", null);
    Map<String, Set<String>> out = GroupRoleResolver.foldRows(List.of(nullOrg, nullRole));
    assertTrue(out.isEmpty());
  }

  @Test
  void foldRows_result_inner_set_is_unmodifiable() {
    GroupRoleMappingRow admin = row(1, "acme", "/acme/admins", "ADMIN");
    Map<String, Set<String>> out = GroupRoleResolver.foldRows(List.of(admin));
    Set<String> roles = out.get("acme");
    assertNotNull(roles);
    assertThrows(UnsupportedOperationException.class, () -> roles.add("DEVELOPER"));
  }

  // ── highestRolePerOrg ──────────────────────────────────────────────────────

  @Test
  void highestRolePerOrg_picks_admin_over_developer() {
    Map<String, Set<String>> in =
        Map.of(
            "acme", Set.of("ADMIN", "DEVELOPER"),
            "globex", Set.of("VIEWER"),
            "initech", Set.of("MAINTAINER", "VIEWER"));
    Map<String, String> out = GroupRoleResolver.highestRolePerOrg(in);
    assertEquals("ADMIN", out.get("acme"));
    assertEquals("VIEWER", out.get("globex"));
    assertEquals("MAINTAINER", out.get("initech"));
  }

  @Test
  void highestRolePerOrg_empty_input_is_empty_output() {
    assertTrue(GroupRoleResolver.highestRolePerOrg(Map.of()).isEmpty());
  }

  // ── helper ─────────────────────────────────────────────────────────────────

  private static GroupRoleMappingRow row(long id, String orgId, String groupPath, String role) {
    GroupRoleMappingRow r = new GroupRoleMappingRow();
    r.id = id;
    r.orgId = orgId;
    r.groupPath = groupPath;
    r.role = role;
    return r;
  }
}
