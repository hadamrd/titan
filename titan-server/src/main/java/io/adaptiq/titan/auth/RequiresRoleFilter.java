package io.adaptiq.titan.auth;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import java.lang.reflect.Method;

/**
 * Post-matching JAX-RS filter that ENFORCES {@link RequiresRole} — the actual runtime seam for the
 * per-action RBAC gate (epic #1114, extended to every mutating endpoint by #1174).
 *
 * <h2>Why a filter and not the {@link RequiresRoleInterceptor}</h2>
 *
 * <p>The annotation was originally designed to be enforced by a CDI {@code @AroundInvoke}
 * interceptor ({@link RequiresRoleInterceptor}). That works under classic RESTEasy, but this
 * product runs on <strong>Quarkus REST</strong> ({@code quarkus-rest}), which invokes JAX-RS
 * resource methods directly on the resolved bean instance and <strong>does not route them through
 * the CDI client proxy</strong> — so a business-method interceptor bound to a resource method never
 * fires. The symptom: a scoped-demoted caller reaches the handler and NO {@code rbac_audit} row is
 * written (the gate is decorative). A JAX-RS {@link ContainerRequestFilter} is the
 * Quarkus-idiomatic seam for cross-cutting request gating (same mechanism as {@link
 * PatJobScopeFilter}) and runs reliably.
 *
 * <p>This filter reads the matched method's {@link RequiresRole} (falling back to the class-level
 * annotation), resolves the scope id exactly as the interceptor did — {@link
 * RequiresRole#scopeIdParam()} matched against the request's path parameters, else {@link
 * RequiresRole#scopeId()} as a literal — and delegates the decision + audit emission to the
 * existing {@link ScopedAuthz#requires} seam. The decision logic, the rank table and the {@code
 * rbac_audit} sink are untouched (per #1174 scope): this class only delivers the call to the seam.
 *
 * <h2>Pipeline order</h2>
 *
 * <p>{@code authentication → @RolesAllowed authorization → PatJobScopeFilter → THIS → resource
 * method}. We run at {@link Priorities#AUTHORIZATION} {@code + 2} so the coarse
 * {@code @RolesAllowed} gate has already 401/403'd anonymous / wrong-realm callers before the
 * per-scope check runs — the same ordering intent the interceptor had with
 * {@code @Priority(APPLICATION + 10)}.
 *
 * <h2>Deny / misconfiguration</h2>
 *
 * <p>On deny, {@link ScopedAuthz#requires} records the {@code DENY} {@code rbac_audit} row and
 * throws {@link io.quarkus.security.ForbiddenException}, which {@link
 * io.adaptiq.titan.api.exception.ForbiddenExceptionMapper} renders as a 403 {@code
 * application/problem+json}. An unresolvable scope id (neither a matching path param nor a literal)
 * is a gate misconfiguration and fails closed with a 403 rather than silently skipping the check.
 */
@Provider
@Priority(Priorities.AUTHORIZATION + 2)
public class RequiresRoleFilter implements ContainerRequestFilter {

  @Context ResourceInfo resourceInfo;

  private final AuthContext auth;
  private final ScopedAuthz scopedAuthz;

  @Inject
  RequiresRoleFilter(AuthContext auth, ScopedAuthz scopedAuthz) {
    this.auth = auth;
    this.scopedAuthz = scopedAuthz;
  }

  @Override
  public void filter(ContainerRequestContext ctx) {
    Method method = resourceInfo.getResourceMethod();
    if (method == null) {
      // No matched resource method (e.g. a 404 short-circuit). Nothing to gate.
      return;
    }
    Class<?> resourceClass = resourceInfo.getResourceClass();
    RequiresRole ann = method.getAnnotation(RequiresRole.class);
    if (ann == null && resourceClass != null) {
      ann = resourceClass.getAnnotation(RequiresRole.class);
    }
    if (ann == null) {
      // Ungated endpoint (or an EXEMPT one — e.g. the HMAC-signed webhooks). Let it through; the
      // RbacEndpointCoverageTest meta-test guarantees no MUTATING verb reaches here ungated.
      return;
    }

    String endpoint =
        (resourceClass != null
                ? resourceClass.getSimpleName()
                : method.getDeclaringClass().getSimpleName())
            + "."
            + method.getName();

    String scopeId = resolveScopeId(ann, ctx);
    if (scopeId == null || scopeId.isBlank()) {
      // Misconfiguration: neither scopeIdParam (matched against the path) nor scopeId literal
      // yields a scope. Fail closed — refuse rather than leak an unscoped check.
      throw new io.quarkus.security.ForbiddenException(
          "RBAC: @RequiresRole on " + endpoint + " has no resolvable scope id");
    }

    // Delegates the decision, the realm-floor fallback AND the rbac_audit ALLOW/DENY row to the
    // existing seam; throws io.quarkus.security.ForbiddenException (→ 403) on deny.
    scopedAuthz.requires(auth, ann.role(), ann.kind(), scopeId, endpoint);
  }

  /**
   * Resolve the scope id from the request. Precedence mirrors {@link
   * RequiresRoleInterceptor#resolveScopeId}: {@link RequiresRole#scopeIdParam()} read from the
   * matched path parameters wins over the {@link RequiresRole#scopeId()} literal.
   */
  static String resolveScopeId(RequiresRole ann, ContainerRequestContext ctx) {
    String paramName = ann.scopeIdParam();
    if (paramName != null && !paramName.isBlank()) {
      // JAX-RS has already URL-decoded the path-parameter value by the time this filter runs.
      return ctx.getUriInfo().getPathParameters().getFirst(paramName);
    }
    String literal = ann.scopeId();
    return literal == null || literal.isBlank() ? null : literal;
  }
}
