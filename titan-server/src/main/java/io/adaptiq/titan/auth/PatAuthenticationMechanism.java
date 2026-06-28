package io.adaptiq.titan.auth;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.credential.TokenCredential;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.identity.request.TokenAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpCredentialTransport;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpHeaders;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Quarkus {@link HttpAuthenticationMechanism} that dispatches {@code Authorization: Bearer
 * titanpat_…} requests through {@link PatTokenVerifier} and lets every other request fall through
 * to the standard OIDC mechanism (closes #477).
 *
 * <h2>Coexistence with OIDC</h2>
 *
 * The discriminator is the bearer PREFIX, NOT the request shape: a request with no Authorization
 * header, or with a Bearer that does not start with {@code titanpat_}, returns {@link
 * Uni#createFrom()}.{@code nullItem()} from {@link #authenticate}. Quarkus then tries the next
 * mechanism in priority order — i.e. the OIDC bearer-token mechanism shipped by {@code
 * quarkus-oidc}. {@link #getChallenge} also returns null on the same condition, so the OIDC
 * mechanism owns the 401 challenge for non-PAT requests (preserving its {@code WWW-Authenticate}
 * shape and any future PKCE redirect behaviour).
 *
 * <h2>Priority (closes #527)</h2>
 *
 * Overrides {@link #getPriority()} to return {@code DEFAULT_PRIORITY + 2} (= 1002) so this
 * mechanism sorts AHEAD of {@code OidcAuthenticationMechanism} (which returns {@code
 * DEFAULT_PRIORITY + 1} = 1001). Quarkus's {@code HttpAuthenticator} sorts mechanisms in descending
 * priority and walks the list in {@code createSecurityIdentity}: each {@code authenticate()} that
 * returns {@link Uni#createFrom()}.{@code nullItem()} hands off to the next, but a {@link
 * Uni#createFrom()}.{@code failure(...)} short-circuits the chain. Before this PR the OIDC
 * mechanism ran first, tried to introspect the opaque {@code titanpat_…} bearer against Keycloak,
 * failed, and propagated the failure — the PAT mechanism never saw the request. With PAT at
 * priority 1002 it now claims PAT-shaped requests first and explicitly punts (null) for every other
 * shape, restoring the documented OIDC fallback path.
 *
 * <p>NOTE: the {@code jakarta.annotation.Priority} annotation is NOT consulted by Quarkus' {@code
 * HttpAuthenticator} — only the overridden {@code getPriority()} method is. A prior attempt at this
 * fix set {@code @Priority(1000)} on the bean and was a no-op for exactly that reason. The CDI bean
 * stays {@link ApplicationScoped}; one instance per app.
 *
 * <h2>Granted roles</h2>
 *
 * Mirrors GitHub PATs (and design §2b.5): a PAT acts as its owning user. Until per-token scopes
 * land (follow-up), every active PAT grants the full non-admin role set ({@code READ_JOB}, {@code
 * TRIGGER_BUILD}, {@code EDIT_PIPELINE}, {@code APPROVE_GATE}, {@code READ_AUDIT}). {@code ADMIN}
 * is NOT granted via PAT — admin paths still require an OIDC session with the admin role claim.
 *
 * <h2>What this does NOT touch</h2>
 *
 * The OIDC config in {@code application.properties} is unchanged. No new {@code IdentityProvider}
 * is registered globally — the mechanism builds the {@link SecurityIdentity} inline because the
 * data source (the {@code titan.personal_access_tokens} table) is owned by titan-server itself, so
 * the indirection of a provider would add ceremony without value.
 */
@ApplicationScoped
public class PatAuthenticationMechanism implements HttpAuthenticationMechanism {

  /**
   * Mechanism priority. {@code DEFAULT_PRIORITY + 2} = 1002 — one above OIDC's {@code
   * DEFAULT_PRIORITY + 1} = 1001, so {@code HttpAuthenticator} dispatches PAT-shaped requests to
   * this mechanism FIRST. See class-level Javadoc for the full rationale (closes #527).
   */
  static final int PAT_MECHANISM_PRIORITY = HttpAuthenticationMechanism.DEFAULT_PRIORITY + 2;

  private static final String BEARER_SCHEME = "Bearer ";

  /**
   * Non-admin roles granted to every PAT. Mirrors the coarse role set we expect a typical CI / CLI
   * user to need. Access can be narrowed further per-token: a PAT may be job-scoped with a glob
   * over a job's full name, and requests outside that pattern are rejected with 403 plus an audit
   * record. See {@code docs/guides/security.md} for the operator-facing security model.
   */
  private static final Set<String> PAT_ROLES;

  static {
    Set<String> roles = new HashSet<>();
    roles.add(Roles.READ_JOB);
    roles.add(Roles.TRIGGER_BUILD);
    roles.add(Roles.EDIT_PIPELINE);
    roles.add(Roles.APPROVE_GATE);
    roles.add(Roles.READ_AUDIT);
    roles.add(Roles.ABORT_BUILD);
    roles.add(Roles.OPERATE_WORKER);
    roles.add(Roles.REPLAY_BUILD);
    roles.add(Roles.MANAGE_CREDENTIALS);
    PAT_ROLES = Set.copyOf(roles);
  }

  private final PatTokenVerifier verifier;

  PatAuthenticationMechanism(PatTokenVerifier verifier) {
    this.verifier = verifier;
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String bearer = extractPatBearer(context);
    if (bearer == null) {
      // Not a PAT-shaped request — let the next mechanism (OIDC) handle it.
      return Uni.createFrom().nullItem();
    }
    Optional<PatTokenVerifier.VerifiedPat> verified = verifier.verify(bearer);
    if (verified.isEmpty()) {
      // PAT-shaped but invalid / revoked → fail authentication (401). We OWN this request; we do
      // NOT punt to OIDC, because the user clearly intended PAT auth and falling through to OIDC
      // would surface a confusing JWT-parse error.
      return Uni.createFrom()
          .failure(new AuthenticationFailedException("invalid personal access token"));
    }
    PatTokenVerifier.VerifiedPat v = verified.get();
    SecurityIdentity identity = buildIdentity(v, bearer);
    return Uni.createFrom().item(identity);
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    String bearer = extractPatBearer(context);
    if (bearer == null) {
      // Let OIDC produce the challenge for non-PAT requests — preserves the WWW-Authenticate shape
      // OIDC clients (and the SPA) expect.
      return Uni.createFrom().nullItem();
    }
    // PAT-shaped but invalid → 401 with a minimal Bearer challenge. We deliberately do NOT echo
    // the bearer or the prefix in any header.
    ChallengeData challenge =
        new ChallengeData(
            401, "WWW-Authenticate", "Bearer realm=\"titan\", error=\"invalid_token\"");
    return Uni.createFrom().item(challenge);
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of(TokenAuthenticationRequest.class);
  }

  @Override
  public int getPriority() {
    return PAT_MECHANISM_PRIORITY;
  }

  @Override
  public Uni<HttpCredentialTransport> getCredentialTransport(RoutingContext context) {
    return Uni.createFrom()
        .item(new HttpCredentialTransport(HttpCredentialTransport.Type.AUTHORIZATION, "Bearer"));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Return the bearer string IFF it carries the {@code titanpat_} prefix, else {@code null}. Never
   * logs the bearer.
   */
  private static String extractPatBearer(RoutingContext context) {
    String header = context.request().getHeader(HttpHeaders.AUTHORIZATION);
    if (header == null || !header.startsWith(BEARER_SCHEME)) {
      return null;
    }
    String bearer = header.substring(BEARER_SCHEME.length()).trim();
    if (!PatTokenVerifier.looksLikePat(bearer)) {
      return null;
    }
    return bearer;
  }

  private static SecurityIdentity buildIdentity(
      PatTokenVerifier.VerifiedPat verified, String bearer) {
    // TokenCredential is the standard Quarkus carrier for bearer auth — downstream code that
    // wants the raw bearer (rare) can pull it from the identity's credentials. We use it here
    // mainly so isAnonymous() returns false consistently with the OIDC path.
    TokenCredential credential = new TokenCredential(bearer, "bearer");
    // #500: roles = creator's PAT-eligible roles ∩ per-PAT scopes.
    // PAT_ROLES is the upper bound (PATs never carry ADMIN); the intersection narrows it further
    // when the token was minted with explicit scopes. A legacy token (scopes=null) keeps the full
    // PAT_ROLES set, preserving #477's behaviour exactly.
    Set<String> effectiveRoles = PatScopes.intersect(PAT_ROLES, verified.scopes());
    QuarkusSecurityIdentity.Builder builder =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(verified.userSubject()))
            .addCredential(credential)
            .addRoles(effectiveRoles)
            // Mirror the OIDC `sub` claim so AuthContext + PersonalAccessTokenApi.requireSubject()
            // pick the same identity attribute for PAT-authenticated callers.
            .addAttribute("sub", verified.userSubject())
            .addAttribute("titan.pat.id", verified.tokenId());
    // #1082: surface the per-PAT job-name glob so PatJobScopeFilter can enforce it
    // post-routing. Absent attribute = no path restriction, preserving #477/#500 behaviour.
    if (verified.jobPattern() != null) {
      builder.addAttribute(PAT_JOB_PATTERN_ATTR, verified.jobPattern());
    }
    return builder.build();
  }

  /**
   * Identity-attribute key for the per-PAT job-name glob (#1082). Read by {@code
   * PatJobScopeFilter}. Absent on the identity = no restriction; present = a validated glob.
   */
  public static final String PAT_JOB_PATTERN_ATTR = "titan.pat.jobPattern";
}
