package io.adaptiq.titan.auth;

import io.adaptiq.titan.auth.Authz.TitanRole;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative per-action RBAC gate — the seam {@link ScopedAuthz} enforces, closes #1131 (epic
 * #1114).
 *
 * <p>Annotate a JAX-RS resource method to require at minimum {@code role} on the request's scope.
 * The {@link RequiresRoleInterceptor} fires before the method body, resolves the caller's effective
 * role on the scope (highest-wins across the scoped {@code titan.rbac_user_role} table, falling
 * through to the org parent and then to the flat {@code titan.user_roles}), and either lets the
 * call proceed or throws {@link io.quarkus.security.ForbiddenException} (→ HTTP 403). Every call —
 * allow or deny — emits one row in {@code titan.audit_log} with action {@code RBAC_CHECK}.
 *
 * <p><strong>Scope resolution.</strong> The interceptor picks the scope id from the request in one
 * of two ways:
 *
 * <ul>
 *   <li>If {@link #scopeIdParam()} names a JAX-RS path parameter, the interceptor reads the
 *       matching method argument as the scope id (e.g. {@code @RequiresRole(role=ADMIN, kind=REPO,
 *       scopeIdParam="repoId")}).
 *   <li>If {@link #scopeIdParam()} is blank, the interceptor falls back to {@link #scopeId()} as a
 *       literal (e.g. the default org slug {@code "global"}).
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>{@code
 * @POST @Path("{id}/cancel")
 * @RequiresRole(role = TitanRole.DEVELOPER, kind = ScopeKind.ORG, scopeId = "global")
 * public Response cancel(@PathParam("id") long id) { ... }
 *
 * @DELETE @Path("/repos/{repoId}")
 * @RequiresRole(role = TitanRole.ADMIN, kind = ScopeKind.REPO, scopeIdParam = "repoId")
 * public Response delete(@PathParam("repoId") String repoId) { ... }
 * }</pre>
 *
 * <p>Why this exists alongside {@link Authz#requires}: {@code Authz.requires} gates a closed enum
 * of {@link Action} constants with hardcoded policy (BUILD_RERUN, PIPELINE_EDIT).
 * {@code @RequiresRole} gates by raw role floor + scope — the seam #1114 needs for tens of
 * endpoints without growing the {@code Action} enum to match.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresRole {

  /** Minimum role the caller must hold on the resolved scope. */
  TitanRole role();

  /** Scope kind the role is required on. */
  ScopeKind kind();

  /**
   * Literal scope id (e.g. the org slug {@code "global"}). Used when the gated endpoint does not
   * carry the scope in its path. Ignored if {@link #scopeIdParam()} is set.
   */
  String scopeId() default "";

  /**
   * Name of the JAX-RS {@code @PathParam} carrying the scope id. The interceptor reads the matching
   * method argument and uses its {@code toString()} value. Takes precedence over {@link
   * #scopeId()}.
   */
  String scopeIdParam() default "";
}
