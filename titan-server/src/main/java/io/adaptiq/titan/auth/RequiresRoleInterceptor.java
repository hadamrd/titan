package io.adaptiq.titan.auth;

import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.PathParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * CDI interceptor for {@link RequiresRole} — closes #1131 (epic #1114).
 *
 * <p>Resolves the scope id from the annotated method's arguments (via {@code @PathParam} match
 * against {@link RequiresRole#scopeIdParam()}) or from {@link RequiresRole#scopeId()} as a literal,
 * then delegates the actual decision + audit emission to {@link ScopedAuthz#requires}. On deny the
 * delegated call throws {@code io.quarkus.security.ForbiddenException} which the existing {@code
 * ForbiddenExceptionMapper} maps to HTTP 403.
 *
 * <p>Priority: runs at {@link Interceptor.Priority#APPLICATION} + 10 so it fires AFTER Quarkus
 * security ({@code @RolesAllowed}) — coarse Quarkus realm-role gates first, then per-scope RBAC.
 */
@RequiresRole(role = Authz.TitanRole.VIEWER, kind = ScopeKind.ORG)
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)
public class RequiresRoleInterceptor {

  private final AuthContext auth;
  private final ScopedAuthz scopedAuthz;

  RequiresRoleInterceptor(AuthContext auth, ScopedAuthz scopedAuthz) {
    this.auth = auth;
    this.scopedAuthz = scopedAuthz;
  }

  @AroundInvoke
  public Object enforce(InvocationContext ctx) throws Exception {
    Method m = ctx.getMethod();
    RequiresRole ann = m.getAnnotation(RequiresRole.class);
    if (ann == null) {
      ann = m.getDeclaringClass().getAnnotation(RequiresRole.class);
    }
    if (ann == null) {
      return ctx.proceed();
    }
    String scopeId = resolveScopeId(ann, m, ctx.getParameters());
    if (scopeId == null || scopeId.isBlank()) {
      // Misconfiguration: neither scopeIdParam nor scopeId set, or path-param missing.
      // Fail closed — refuse the call rather than silently leak an unscoped check.
      throw new io.quarkus.security.ForbiddenException(
          "RBAC: @RequiresRole on "
              + m.getDeclaringClass().getSimpleName()
              + "."
              + m.getName()
              + " has no resolvable scope id");
    }
    String endpoint = m.getDeclaringClass().getSimpleName() + "." + m.getName();
    scopedAuthz.requires(auth, ann.role(), ann.kind(), scopeId, endpoint);
    return ctx.proceed();
  }

  /**
   * Find the scope id from the method invocation. Precedence: {@code scopeIdParam} (matched against
   * each parameter's {@code @PathParam} value) over {@code scopeId} literal.
   */
  static String resolveScopeId(RequiresRole ann, Method m, Object[] args) {
    String paramName = ann.scopeIdParam();
    if (paramName != null && !paramName.isBlank()) {
      Parameter[] params = m.getParameters();
      for (int i = 0; i < params.length; i++) {
        PathParam pp = null;
        for (Annotation a : params[i].getAnnotations()) {
          if (a instanceof PathParam p) {
            pp = p;
            break;
          }
        }
        if (pp != null && paramName.equals(pp.value())) {
          Object v = args[i];
          return v == null ? null : v.toString();
        }
      }
      return null;
    }
    String literal = ann.scopeId();
    return literal == null || literal.isBlank() ? null : literal;
  }
}
