package io.adaptiq.titan.auth;

import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.auth.Authz.TitanRole;
import io.adaptiq.titan.store.GroupRoleMappingDao;
import io.adaptiq.titan.store.TitanStores;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.Principal;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Apply the SSO group → Titan role mapping at login (closes #1235, EPIC #1114 item 4).
 *
 * <p>Quarkus auto-discovers every {@link SecurityIdentityAugmentor} CDI bean and runs it on the
 * {@link SecurityIdentity} of every authenticated request. This augmentor reads the OIDC {@code
 * groups} claim, resolves it through {@link GroupRoleResolver} against the persisted {@code
 * group_role_mapping} rows ({@link io.adaptiq.titan.api.SsoMappingApi} CRUD), and injects the
 * resulting Titan roles onto the live identity so {@code @RolesAllowed}, {@link ScopedAuthz} and
 * {@link AuthContext#currentRoles()} all observe them. Before this seam, {@link
 * GroupRoleResolver#resolve} had no production caller — the mapping was inert.
 *
 * <h2>How a group grant becomes an enforced role</h2>
 *
 * <p>The resolver yields {@code Map<orgId, Set<TitanRole-name>>}. Each resolved {@link TitanRole}
 * is expanded to the <em>downward closure</em> of canonical Quarkus realm roles it implies via
 * {@link ScopedAuthz#canonicalRealmRoles} and those realm roles are added to the identity. This
 * reuses the ONE existing realm-floor mechanism ({@link ScopedAuthz#roleFloorFromRealm}) so a
 * group-derived role clears both the per-scope {@code @RequiresRole} gate (which floors realm roles
 * back to a {@link TitanRole}) and every coarse {@code @RolesAllowed} gate at or below its tier —
 * Quarkus {@code @RolesAllowed} is a literal membership check with no hierarchy, so a single realm
 * role would leave a MAINTAINER 403'd on {@code @RolesAllowed(READ_JOB)} endpoints. Example: {@code
 * /release-eng → MAINTAINER} adds {@link Roles#READ_JOB}, the build/approve roles, and {@link
 * Roles#EDIT_PIPELINE}; a MAINTAINER-gated endpoint now admits the caller with no DB role seed AND
 * the caller can still read jobs/builds.
 *
 * <p><strong>Org scoping.</strong> Realm roles are flat, matching the existing realm-floor model
 * (per-org admin delegation is explicitly out of scope for #1235 — single-tenant {@code "global"}).
 * The union of roles across all of the subject's mapped orgs is injected.
 *
 * <h2>Adversarial inputs never elevate and never 500</h2>
 *
 * <ul>
 *   <li>Anonymous request → returned unchanged (no claim, no DB read).
 *   <li>Absent / null / wrong-typed / CSV {@code groups} claim → {@link GroupRoleResolver#resolve}
 *       coerces or drops it; empty resolution → identity returned unchanged (falls to realm-floor /
 *       VIEWER).
 *   <li>Groups that map to nothing → empty resolution → no roles added.
 * </ul>
 *
 * <p><strong>Caching.</strong> Resolution is per-request (the augmentor runs every request), so
 * adding/removing an {@code SsoMappingApi} row changes the effective role on the very next request
 * — no restart. Caching beyond per-request is intentionally not done here (#1235 out-of-scope).
 */
@ApplicationScoped
public class GroupRoleAugmentor implements SecurityIdentityAugmentor {

  private static final Logger LOGGER = Logger.getLogger(GroupRoleAugmentor.class.getName());

  /** OIDC claim carrying the caller's full Keycloak group paths (e.g. {@code "/release-eng"}). */
  static final String GROUPS_CLAIM = "groups";

  private final GroupRoleResolver resolver;

  /**
   * CDI constructor. Injects the {@link TitanStores} façade — the actual application-scoped bean —
   * and reads the {@link GroupRoleMappingDao} off it. The DAO is a JDBI {@code SqlObject}
   * interface, NOT a CDI bean, so it cannot be injected directly: doing so makes this augmentor an
   * unsatisfied dependency and Quarkus bean validation fails the build. Going through {@code
   * TitanStores} mirrors how {@code SsoMappingApi} obtains the same DAO.
   */
  @Inject
  GroupRoleAugmentor(TitanStores stores) {
    this(stores.groupRoleMapping());
  }

  /** Test seam: build directly over a {@link GroupRoleMappingDao} (real or in-memory fake). */
  GroupRoleAugmentor(GroupRoleMappingDao groupRoleMappingDao) {
    this.resolver = new GroupRoleResolver(groupRoleMappingDao);
  }

  @Override
  public Uni<SecurityIdentity> augment(
      SecurityIdentity identity, AuthenticationRequestContext context) {
    if (identity == null || identity.isAnonymous()) {
      return Uni.createFrom().item(identity);
    }
    // resolve() hits the DB (DAO) — offload to a worker thread; the augmentor may run on the IO
    // thread. runBlocking returns a Uni completed on a worker pool.
    return context.runBlocking(() -> augmentBlocking(identity));
  }

  /**
   * Synchronous core — package-private so the unit test exercises it directly without a Mutiny
   * scheduler. Reads the groups claim, resolves it, and returns either the original identity (when
   * nothing resolves) or a copy with the group-derived realm roles added.
   */
  SecurityIdentity augmentBlocking(SecurityIdentity identity) {
    Object rawGroups = extractGroupsClaim(identity);
    Map<String, Set<String>> byOrg = resolver.resolve(rawGroups);
    if (byOrg.isEmpty()) {
      return identity;
    }
    Set<String> realmRoles = new LinkedHashSet<>();
    for (Set<String> roles : byOrg.values()) {
      for (String roleName : roles) {
        TitanRole role = parseRole(roleName);
        if (role != null) {
          realmRoles.addAll(ScopedAuthz.canonicalRealmRoles(role));
        }
      }
    }
    if (realmRoles.isEmpty()) {
      return identity;
    }
    LOGGER.log(
        Level.FINE,
        "[titan] SSO group mapping granted realm roles {0} to {1}",
        new Object[] {realmRoles, identity.getPrincipal().getName()});
    return QuarkusSecurityIdentity.builder(identity).addRoles(realmRoles).build();
  }

  /**
   * Read the {@code groups} claim from the identity. Prefers the OIDC {@link JsonWebToken}
   * principal (the production login path), falling back to a same-named {@link SecurityIdentity}
   * attribute. Returns {@code null} when absent — {@link GroupRoleResolver#resolve} treats that as
   * "no groups".
   */
  @Nullable
  static Object extractGroupsClaim(SecurityIdentity identity) {
    Principal principal = identity.getPrincipal();
    if (principal instanceof JsonWebToken jwt) {
      Object claim = jwt.getClaim(GROUPS_CLAIM);
      if (claim != null) {
        return claim;
      }
    }
    return identity.getAttribute(GROUPS_CLAIM);
  }

  @Nullable
  private static TitanRole parseRole(@Nullable String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return TitanRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      // The resolver already filters to GroupMappingValidator.SUPPORTED_ROLES; this is belt-and-
      // suspenders against a future role string drifting out of the TitanRole enum. Drop, never
      // elevate.
      return null;
    }
  }
}
