package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.GroupRoleMappingDao;
import io.adaptiq.titan.store.rows.GroupRoleMappingRow;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.List;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link GroupRoleAugmentor} — the login seam that makes the SSO group → role
 * mapping enforceable (closes #1235).
 *
 * <p>Adversarial-first per the testing manifesto: every claim-shape that must NOT crash and must
 * NOT elevate gets a named test (absent, null, CSV string, non-list type, unmapped group,
 * anonymous). The happy path asserts the canonical realm role is added on top of any existing
 * roles.
 */
class GroupRoleAugmentorTest {

  // ── happy path ─────────────────────────────────────────────────────────────

  @Test
  void maintainer_group_adds_canonical_realm_role() {
    GroupRoleAugmentor aug = augmentorWith(row("/release-eng", "global", "MAINTAINER"));
    SecurityIdentity in = identity("alice", List.of("/release-eng"));

    SecurityIdentity out = aug.augmentBlocking(in);

    assertTrue(
        out.getRoles().contains(Roles.EDIT_PIPELINE),
        "MAINTAINER group → canonical realm role EDIT_PIPELINE; got " + out.getRoles());
    // Downward closure: a MAINTAINER must ALSO carry the lower-tier realm roles, else Quarkus
    // @RolesAllowed (literal membership, no hierarchy) 403s them on every READ_JOB-gated endpoint.
    assertTrue(
        out.getRoles().contains(Roles.READ_JOB),
        "MAINTAINER group must also grant READ_JOB so read endpoints admit it; got "
            + out.getRoles());
    assertTrue(
        out.getRoles().contains(Roles.TRIGGER_BUILD),
        "MAINTAINER group must also grant the DEVELOPER-tier realm roles; got " + out.getRoles());
  }

  @Test
  void admin_group_adds_admin_realm_role() {
    GroupRoleAugmentor aug = augmentorWith(row("/platform", "global", "ADMIN"));
    SecurityIdentity out = aug.augmentBlocking(identity("root", List.of("/platform")));
    assertTrue(out.getRoles().contains(Roles.ADMIN), out.getRoles().toString());
  }

  @Test
  void resolved_role_is_additive_to_existing_roles() {
    GroupRoleAugmentor aug = augmentorWith(row("/release-eng", "global", "MAINTAINER"));
    SecurityIdentity in =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("alice"))
            .addRole(Roles.READ_JOB)
            .addAttribute(GroupRoleAugmentor.GROUPS_CLAIM, List.of("/release-eng"))
            .build();

    SecurityIdentity out = aug.augmentBlocking(in);

    assertTrue(out.getRoles().contains(Roles.READ_JOB), "pre-existing role preserved");
    assertTrue(out.getRoles().contains(Roles.EDIT_PIPELINE), "group role added");
  }

  @Test
  void full_augment_uni_path_adds_role() {
    GroupRoleAugmentor aug = augmentorWith(row("/release-eng", "global", "MAINTAINER"));
    SecurityIdentity out =
        aug.augment(identity("alice", List.of("/release-eng")), inlineContext())
            .await()
            .indefinitely();
    assertTrue(out.getRoles().contains(Roles.EDIT_PIPELINE), out.getRoles().toString());
  }

  // ── adversarial: never elevate, never throw ────────────────────────────────

  @Test
  void absent_groups_claim_adds_nothing() {
    GroupRoleAugmentor aug = augmentorWith(row("/release-eng", "global", "MAINTAINER"));
    SecurityIdentity in =
        QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal("nobody")).build();

    SecurityIdentity out = aug.augmentBlocking(in);

    assertSame(in, out, "no claim → identity returned unchanged");
    assertFalse(out.getRoles().contains(Roles.EDIT_PIPELINE));
  }

  @Test
  void unmapped_group_adds_nothing() {
    GroupRoleAugmentor aug = augmentorWith(row("/release-eng", "global", "MAINTAINER"));
    SecurityIdentity out = aug.augmentBlocking(identity("alice", List.of("/not-mapped")));
    assertFalse(out.getRoles().contains(Roles.EDIT_PIPELINE), out.getRoles().toString());
  }

  @Test
  void non_list_claim_type_does_not_throw_and_adds_nothing() {
    GroupRoleAugmentor aug = augmentorWith(row("/release-eng", "global", "MAINTAINER"));
    SecurityIdentity in =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("alice"))
            .addAttribute(GroupRoleAugmentor.GROUPS_CLAIM, Integer.valueOf(42))
            .build();

    SecurityIdentity out = aug.augmentBlocking(in);

    assertSame(in, out);
    assertTrue(out.getRoles().isEmpty(), out.getRoles().toString());
  }

  @Test
  void csv_string_claim_is_coerced_not_crashed() {
    GroupRoleAugmentor aug = augmentorWith(row("/release-eng", "global", "MAINTAINER"));
    SecurityIdentity in =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("alice"))
            .addAttribute(GroupRoleAugmentor.GROUPS_CLAIM, "/other,/release-eng")
            .build();

    SecurityIdentity out = aug.augmentBlocking(in);

    assertTrue(
        out.getRoles().contains(Roles.EDIT_PIPELINE),
        "CSV claim coerced to a list, mapped group resolved; got " + out.getRoles());
  }

  @Test
  void anonymous_identity_is_returned_unchanged() {
    GroupRoleAugmentor aug = augmentorWith(row("/release-eng", "global", "MAINTAINER"));
    SecurityIdentity anon = QuarkusSecurityIdentity.builder().setAnonymous(true).build();
    SecurityIdentity out = aug.augment(anon, inlineContext()).await().indefinitely();
    assertSame(anon, out);
    assertTrue(out.getRoles().isEmpty());
  }

  // ── fixtures ───────────────────────────────────────────────────────────────

  private static SecurityIdentity identity(String user, List<String> groups) {
    return QuarkusSecurityIdentity.builder()
        .setPrincipal(new QuarkusPrincipal(user))
        .addAttribute(GroupRoleAugmentor.GROUPS_CLAIM, groups)
        .build();
  }

  private static io.quarkus.security.identity.AuthenticationRequestContext inlineContext() {
    return supplier -> Uni.createFrom().item(supplier);
  }

  private static GroupRoleMappingRow row(String groupPath, String orgId, String role) {
    GroupRoleMappingRow r = new GroupRoleMappingRow();
    r.id = Math.abs(groupPath.hashCode());
    r.groupPath = groupPath;
    r.orgId = orgId;
    r.role = role;
    return r;
  }

  private static GroupRoleAugmentor augmentorWith(GroupRoleMappingRow... rows) {
    return new GroupRoleAugmentor(new FakeGroupRoleMappingDao(List.of(rows)));
  }

  /**
   * In-memory {@link GroupRoleMappingDao} — only {@link #listByGroupPaths} is exercised by the
   * augmentor; every other surface throws so an accidental future dependency is loud, not silent.
   */
  private static final class FakeGroupRoleMappingDao implements GroupRoleMappingDao {
    private final List<GroupRoleMappingRow> rows;

    FakeGroupRoleMappingDao(List<GroupRoleMappingRow> rows) {
      this.rows = rows;
    }

    @Override
    public List<GroupRoleMappingRow> listByGroupPaths(List<String> paths) {
      List<GroupRoleMappingRow> out = new ArrayList<>();
      if (paths == null) {
        return out;
      }
      for (GroupRoleMappingRow r : rows) {
        if (paths.contains(r.groupPath)) {
          out.add(r);
        }
      }
      return out;
    }

    @Override
    public List<GroupRoleMappingRow> listByOrg(String orgId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<GroupRoleMappingRow> findById(long id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public java.util.Optional<GroupRoleMappingRow> findByOrgAndGroup(String orgId, String group) {
      throw new UnsupportedOperationException();
    }

    @Override
    public long insert(String orgId, String groupPath, String role) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int updateRole(long id, String role) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int delete(long id) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Handle getHandle() {
      throw new UnsupportedOperationException();
    }

    @Override
    public <R, X extends Exception> R withHandle(org.jdbi.v3.core.HandleCallback<R, X> callback) {
      throw new UnsupportedOperationException();
    }
  }
}
