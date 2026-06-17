package io.adaptiq.titan.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.adaptiq.titan.auth.Authz.TitanRole;
import jakarta.ws.rs.PathParam;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link RequiresRoleInterceptor#resolveScopeId}. We exercise the path-param
 * matching, the literal fallback, and the misconfig case where neither is set — the last one is the
 * "fail closed" branch that prevents silent unscoped checks (closes #1131).
 */
class RequiresRoleInterceptorTest {

  // ── fixtures ─────────────────────────────────────────────────────────────

  @SuppressWarnings("unused")
  static class Fixture {
    @RequiresRole(role = TitanRole.ADMIN, kind = ScopeKind.REPO, scopeIdParam = "repoId")
    public void byPathParam(@PathParam("repoId") String repoId) {}

    @RequiresRole(role = TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
    public void byLiteral() {}

    @RequiresRole(role = TitanRole.ADMIN, kind = ScopeKind.REPO, scopeIdParam = "missing")
    public void unresolved(@PathParam("repoId") String repoId) {}

    @RequiresRole(role = TitanRole.ADMIN, kind = ScopeKind.ORG)
    public void misconfigured() {}

    @RequiresRole(role = TitanRole.ADMIN, kind = ScopeKind.REPO, scopeIdParam = "id")
    public void numericId(@PathParam("id") long id) {}
  }

  private static Method method(String name) {
    for (Method m : Fixture.class.getDeclaredMethods()) {
      if (m.getName().equals(name)) {
        return m;
      }
    }
    throw new IllegalStateException("missing fixture method " + name);
  }

  // ── tests ────────────────────────────────────────────────────────────────

  @Test
  void pathParam_match_returns_arg_value() {
    Method m = method("byPathParam");
    RequiresRole ann = m.getAnnotation(RequiresRole.class);
    assertEquals(
        "acme/widgets",
        RequiresRoleInterceptor.resolveScopeId(ann, m, new Object[] {"acme/widgets"}));
  }

  @Test
  void literal_used_when_no_param_name() {
    Method m = method("byLiteral");
    RequiresRole ann = m.getAnnotation(RequiresRole.class);
    assertEquals("global", RequiresRoleInterceptor.resolveScopeId(ann, m, new Object[] {}));
  }

  /** Adversarial — scopeIdParam set but no matching @PathParam name → null (fail-closed). */
  @Test
  void unresolved_pathParam_returns_null() {
    Method m = method("unresolved");
    RequiresRole ann = m.getAnnotation(RequiresRole.class);
    assertNull(RequiresRoleInterceptor.resolveScopeId(ann, m, new Object[] {"acme/widgets"}));
  }

  /** Adversarial — neither scopeIdParam nor scopeId set → null (interceptor will 403). */
  @Test
  void misconfigured_annotation_returns_null() {
    Method m = method("misconfigured");
    RequiresRole ann = m.getAnnotation(RequiresRole.class);
    assertNull(RequiresRoleInterceptor.resolveScopeId(ann, m, new Object[] {}));
  }

  /** Adversarial — numeric path param is converted via toString(). */
  @Test
  void numericPathParam_isStringified() {
    Method m = method("numericId");
    RequiresRole ann = m.getAnnotation(RequiresRole.class);
    assertEquals("42", RequiresRoleInterceptor.resolveScopeId(ann, m, new Object[] {42L}));
  }

  /** Adversarial — null path-param arg returns null (don't pretend it was "null" literal). */
  @Test
  void nullPathParam_arg_returnsNull() {
    Method m = method("byPathParam");
    RequiresRole ann = m.getAnnotation(RequiresRole.class);
    assertNull(RequiresRoleInterceptor.resolveScopeId(ann, m, new Object[] {null}));
  }
}
