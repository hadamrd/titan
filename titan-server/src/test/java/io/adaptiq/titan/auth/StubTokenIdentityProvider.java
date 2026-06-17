package io.adaptiq.titan.auth;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TokenAuthenticationRequest;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Test-scope no-op {@link IdentityProvider} for {@link TokenAuthenticationRequest}.
 *
 * <p><strong>Why this exists.</strong> Quarkus' boot-time {@code HttpSecurityRecorder} validates
 * that every registered {@link io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism
 * HttpAuthenticationMechanism} whose {@code getCredentialTypes()} declares a credential type has at
 * least one matching {@link IdentityProvider}. The production {@link PatAuthenticationMechanism}
 * declares {@code TokenAuthenticationRequest.class} (see {@code
 * PatAuthenticationMechanism#getCredentialTypes()}), but never actually delegates to an {@link
 * io.quarkus.security.identity.IdentityProviderManager} — it builds the {@link SecurityIdentity}
 * inline from {@link PatTokenVerifier}. In production this validation is satisfied by {@code
 * OidcIdentityProvider} (registered by {@code quarkus-oidc}). In the default unit-test profile
 * {@code quarkus.oidc.enabled=false}, so without this stub Quarkus throws at boot:
 *
 * <pre>
 *   HttpAuthenticationMechanism 'PatAuthenticationMechanism_ClientProxy' requires one or more
 *   IdentityProviders supporting at least one of the following credential types ...
 * </pre>
 *
 * causing every {@code @QuarkusTest} in this source set to fail with a context-init error (216
 * failures as of #556).
 *
 * <p><strong>Why it's safe.</strong> {@code @QuarkusTest} unit tests authenticate via {@link
 * io.quarkus.test.security.TestSecurity @TestSecurity}, which short-circuits the mechanism chain
 * entirely — this provider is never invoked. It exists solely to satisfy the boot-time registration
 * check. If anything ever does call it (e.g. an unauthenticated request reaches a mechanism that
 * delegates here), it fails the authentication explicitly rather than silently granting access.
 *
 * <p>Lives in {@code src/test/java} so it is never packaged into the production jar.
 */
@ApplicationScoped
public class StubTokenIdentityProvider implements IdentityProvider<TokenAuthenticationRequest> {

  @Override
  public Class<TokenAuthenticationRequest> getRequestType() {
    return TokenAuthenticationRequest.class;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      TokenAuthenticationRequest request, AuthenticationRequestContext context) {
    return Uni.createFrom()
        .failure(
            new AuthenticationFailedException(
                "StubTokenIdentityProvider: token auth is not wired in the unit-test profile"));
  }
}
