package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.CreatePersonalAccessTokenRequest;
import io.adaptiq.titan.api.dto.PersonalAccessTokenCreatedDto;
import io.adaptiq.titan.api.dto.PersonalAccessTokenDto;
import io.adaptiq.titan.audit.AuditAction;
import io.adaptiq.titan.audit.AuditService;
import io.adaptiq.titan.audit.AuditTargetType;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.PatJobPattern;
import io.adaptiq.titan.auth.PatScopes;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.store.PersonalAccessTokenDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.PersonalAccessTokenRow;
import io.quarkus.elytron.security.common.BcryptUtil;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Jakarta REST resource: {@code /api/v1/me/tokens} — personal API token management (closes #434).
 *
 * <ul>
 *   <li>{@code GET /api/v1/me/tokens} — list the caller's tokens (metadata only — no plaintext, no
 *       hash)
 *   <li>{@code POST /api/v1/me/tokens} — generate a token; response carries the plaintext
 *       <strong>exactly once</strong>
 *   <li>{@code DELETE /api/v1/me/tokens/{id}} — soft-revoke (sets {@code revoked_at}, keeps the row
 *       for audit)
 * </ul>
 *
 * <p><strong>Security model.</strong>
 *
 * <ul>
 *   <li>OIDC-gated — {@code @RolesAllowed("**")} accepts any authenticated user; the actual scope
 *       comes from the OIDC {@code sub} claim, not from a Keycloak role. ADMIN users cannot list or
 *       revoke tokens that belong to a different subject — there is no admin-override path.
 *   <li>The plaintext token is generated server-side via {@link SecureRandom}, formatted as {@code
 *       "titanpat_" + 32 base32 alphanumerics}, and hashed with {@link BcryptUtil#bcryptHash}
 *       before being persisted. Plaintext is never stored, logged, or returned a second time.
 *   <li>The {@code prefix} column ({@code "titanpat_" + first 4 random chars}) lets the UI
 *       disambiguate rows without exposing a usable secret. It is also the regex anchor (see {@code
 *       titanpat_} prefix) for log-scrubbing / committed-secret detection.
 * </ul>
 *
 * <p>The bearer-token authentication path (verifying an incoming {@code Authorization: Bearer
 * titanpat_*}) is deliberately out of scope here — this PR ships the CRUD surface and the row
 * shape. The auth path lands in a follow-up so it can be reviewed in isolation.
 */
@Path("/api/v1/me/tokens")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class PersonalAccessTokenApi {

  /** {@code "titanpat_"} — the public prefix that anchors token-shaped strings in logs / code. */
  public static final String TOKEN_PREFIX = "titanpat_";

  /**
   * Length of the random suffix (alphanumeric, base32-style) appended after {@link #TOKEN_PREFIX}.
   */
  static final int RANDOM_SUFFIX_LEN = 32;

  /** Length of the public prefix retained on a row for list-view disambiguation. */
  static final int STORED_PREFIX_LEN = TOKEN_PREFIX.length() + 4;

  /** Base32 alphabet — uppercase + digits, no padding, ambiguity-free. */
  private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

  private final TitanStores stores;
  private final SecurityIdentity identity;
  private final AuditService audit;
  private final SecureRandom random;

  PersonalAccessTokenApi(TitanStores stores, SecurityIdentity identity, AuditService audit) {
    this.stores = stores;
    this.identity = identity;
    this.audit = audit;
    this.random = new SecureRandom();
  }

  // ── GET /api/v1/me/tokens ──────────────────────────────────────────────────

  @GET
  @RolesAllowed("**")
  public List<PersonalAccessTokenDto> list() {
    String subject = requireSubject();
    return stores.personalAccessTokens().listByUser(subject).stream()
        .map(PersonalAccessTokenDto::from)
        .toList();
  }

  // ── POST /api/v1/me/tokens ─────────────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed("**")
  @RequiresRole(role = Authz.TitanRole.VIEWER, kind = ScopeKind.ORG, scopeId = "global")
  public Response create(CreatePersonalAccessTokenRequest req) {
    if (req == null) {
      throw new ApiBadRequestException("request body is required");
    }
    String name = req.name();
    if (name == null || name.isBlank()) {
      throw new ApiBadRequestException("field 'name' is required");
    }
    name = name.trim();
    if (name.length() > 100) {
      throw new ApiBadRequestException("field 'name' must be <= 100 characters");
    }
    // Validate optional scopes (#500). null/empty → legacy "inherit all", a normalised list
    // otherwise. Unknown scopes raise 400 problem+json via the exception mapper.
    List<String> scopes;
    try {
      scopes = PatScopes.validate(req.scopes());
    } catch (PatScopes.InvalidPatScopeException e) {
      throw new ApiBadRequestException(e.getMessage());
    }
    String scopesJson = PatScopes.toJson(scopes);
    // Validate optional job-pattern glob (#1082). null/blank → no path restriction, a trimmed
    // glob otherwise. Disallowed characters / path-traversal / over-length all raise 400.
    String jobPattern;
    try {
      jobPattern = PatJobPattern.validate(req.jobPattern());
    } catch (PatJobPattern.InvalidPatJobPatternException e) {
      throw new ApiBadRequestException(e.getMessage());
    }
    String subject = requireSubject();

    String plaintext = TOKEN_PREFIX + randomSuffix();
    String hash = BcryptUtil.bcryptHash(plaintext);
    String prefix = plaintext.substring(0, STORED_PREFIX_LEN);

    PersonalAccessTokenRow row = new PersonalAccessTokenRow();
    row.userSubject = subject;
    row.name = name;
    row.tokenHash = hash;
    row.prefix = prefix;
    row.scopesJson = scopesJson;
    row.jobPattern = jobPattern;

    PersonalAccessTokenDao dao = stores.personalAccessTokens();
    long id;
    try {
      id = dao.insert(row);
    } catch (RuntimeException e) {
      // Most likely a uq-violation on (user_subject, name) — surface as 400 rather than 500.
      if (isUniqueViolation(e)) {
        throw new ApiBadRequestException(
            "a token named '" + name + "' already exists for this user");
      }
      throw e;
    }

    // Re-load to capture DB-stamped created_at so the response is consistent with the list view.
    Optional<PersonalAccessTokenRow> persisted = dao.findByIdForUser(id, subject);
    Instant createdAt = persisted.map(r -> r.createdAt).orElse(null);

    PersonalAccessTokenCreatedDto dto =
        new PersonalAccessTokenCreatedDto(
            id, name, prefix, plaintext, createdAt, scopes, jobPattern);

    // SECURITY: details_json records the row id and the public prefix ONLY — never the plaintext
    // token, never the BCrypt hash. The CONSTITUTION §6 ban on plaintext secrets in any cache /
    // log applies here too.
    audit.record(
        AuditAction.PAT_CREATE,
        AuditTargetType.PAT,
        Long.toString(id),
        "{\"name\":\"" + JobsApi.jsonEscape(name) + "\",\"prefix\":\"" + prefix + "\"}");

    return Response.status(201).entity(dto).build();
  }

  // ── DELETE /api/v1/me/tokens/{id} ──────────────────────────────────────────

  @DELETE
  @Path("/{id}")
  @RolesAllowed("**")
  @RequiresRole(role = Authz.TitanRole.VIEWER, kind = ScopeKind.ORG, scopeId = "global")
  public Response revoke(@PathParam("id") String idStr) {
    long id = JobsApi.parseLong(idStr, "id");
    String subject = requireSubject();
    int updated = stores.personalAccessTokens().revoke(id, subject);
    if (updated == 0) {
      // Indistinguishable from "already revoked" / "not yours" — return 404 in both cases so a
      // probe cannot enumerate other users' token ids.
      throw new ApiNotFoundException("token " + id + " not found");
    }
    audit.record(AuditAction.PAT_REVOKE, AuditTargetType.PAT, Long.toString(id), null);
    return Response.noContent().build();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * Pull the OIDC subject claim from the security identity. Falls back to the principal name — the
   * {@code @QuarkusTest} {@code @TestSecurity} harness wires the test user as the principal without
   * a populated {@code sub} attribute, and we want unit tests to be able to exercise the happy path
   * without standing up Keycloak.
   */
  private String requireSubject() {
    if (identity.isAnonymous()) {
      throw new ApiBadRequestException("authentication required");
    }
    Object sub = identity.getAttribute("sub");
    if (sub instanceof String s && !s.isBlank()) {
      return s;
    }
    String name = identity.getPrincipal().getName();
    if (name == null || name.isBlank()) {
      throw new ApiBadRequestException("authentication required");
    }
    return name;
  }

  private String randomSuffix() {
    char[] out = new char[RANDOM_SUFFIX_LEN];
    for (int i = 0; i < RANDOM_SUFFIX_LEN; i++) {
      out[i] = BASE32[random.nextInt(BASE32.length)];
    }
    return new String(out);
  }

  private static boolean isUniqueViolation(Throwable t) {
    for (Throwable c = t; c != null; c = c.getCause()) {
      String msg = c.getMessage();
      if (msg != null
          && (msg.contains("pat_user_name_unique")
              || msg.contains("Unique index")
              || msg.contains("duplicate key"))) {
        return true;
      }
    }
    return false;
  }
}
