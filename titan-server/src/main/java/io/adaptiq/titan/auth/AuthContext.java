package io.adaptiq.titan.auth;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

/**
 * Application-scoped helper that exposes the current authenticated principal and roles.
 *
 * <p>Injected by constructor wherever a caller needs to inspect the security context without
 * reaching directly into {@link SecurityIdentity}. No business logic lives here — it is a thin
 * facade so downstream code doesn't couple directly to the Quarkus security SPI.
 *
 * <p>Usage example:
 *
 * <pre>{@code
 * private final AuthContext auth;
 *
 * MyBean(AuthContext auth) { this.auth = auth; }
 *
 * void doSomething() {
 *     String user = auth.currentUser();
 *     Set<String> roles = auth.currentRoles();
 * }
 * }</pre>
 */
@ApplicationScoped
public class AuthContext {

  private final SecurityIdentity identity;

  AuthContext(SecurityIdentity identity) {
    this.identity = identity;
  }

  /**
   * Returns the principal name of the currently authenticated user, or {@code "<anonymous>"} for
   * unauthenticated requests.
   */
  public String currentUser() {
    if (identity.isAnonymous()) {
      return "<anonymous>";
    }
    return identity.getPrincipal().getName();
  }

  /**
   * Returns the set of roles granted to the currently authenticated user. Returns an empty set for
   * anonymous requests.
   */
  public Set<String> currentRoles() {
    return identity.getRoles();
  }
}
