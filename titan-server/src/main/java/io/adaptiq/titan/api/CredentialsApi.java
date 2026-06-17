package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.CreateCredentialRequest;
import io.adaptiq.titan.api.dto.CredentialDto;
import io.adaptiq.titan.api.dto.CredentialsPage;
import io.adaptiq.titan.api.dto.UpdateCredentialRequest;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.credentials.Credential;
import io.adaptiq.titan.credentials.CredentialNotFoundException;
import io.adaptiq.titan.credentials.CredentialUpdate;
import io.adaptiq.titan.credentials.CredentialsService;
import io.adaptiq.titan.credentials.NewCredentialRequest;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

/**
 * Jakarta REST resource: {@code /api/v1/credentials} — manage Titan's encrypted secrets store
 * (closes #274).
 *
 * <ul>
 *   <li>{@code GET /api/v1/credentials} — paginated list (metadata only)
 *   <li>{@code GET /api/v1/credentials/{id}} — single credential metadata, or 404
 *   <li>{@code POST /api/v1/credentials} — create (seals plaintext server-side)
 *   <li>{@code PUT /api/v1/credentials/{id}} — rotate the secret value
 *   <li>{@code DELETE /api/v1/credentials/{id}} — remove
 * </ul>
 *
 * <p><strong>Plaintext and the sealed blob never travel over this resource on the way out.</strong>
 * Every response carries {@link CredentialDto} — id, kind, scope, key, timestamps. The plaintext
 * round-trip is one-way: a client supplies it on {@code POST}/{@code PUT}, the server seals it via
 * {@link io.adaptiq.titan.flow.crypto.SecretCipher#seal} and persists ciphertext only. The unseal
 * path is internal and reserved for the engine at step dispatch time ({@link
 * CredentialsService#resolvePlaintext}).
 *
 * <p>Constructor-injection only — {@link CredentialsService} is wired by Quarkus ARC.
 */
@Path("/api/v1/credentials")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class CredentialsApi {

  private final CredentialsService service;

  CredentialsApi(CredentialsService service) {
    this.service = service;
  }

  // ── GET /api/v1/credentials ────────────────────────────────────────────────

  @GET
  @RolesAllowed({Roles.EDIT_PIPELINE, Roles.ADMIN})
  public CredentialsPage list(
      @QueryParam("scope") String scope,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    int cappedLimit = Math.min(Math.max(limit, 0), 200);
    int safeOffset = Math.max(offset, 0);

    List<Credential> all =
        (scope == null || scope.isBlank()) ? service.listAll() : service.listByScope(scope);
    int total = all.size();
    List<CredentialDto> page =
        all.stream().skip(safeOffset).limit(cappedLimit).map(CredentialDto::from).toList();
    return new CredentialsPage(page, total, safeOffset, cappedLimit);
  }

  // ── GET /api/v1/credentials/{id} ───────────────────────────────────────────

  @GET
  @Path("/{id}")
  @RolesAllowed({Roles.EDIT_PIPELINE, Roles.ADMIN})
  public CredentialDto get(@PathParam("id") String idStr) {
    long id = JobsApi.parseLong(idStr, "id");
    Credential c =
        service
            .findById(id)
            .orElseThrow(() -> new ApiNotFoundException("credential " + id + " not found"));
    return CredentialDto.from(c);
  }

  // ── POST /api/v1/credentials ───────────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.MANAGE_CREDENTIALS, Roles.EDIT_PIPELINE, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response create(CreateCredentialRequest req) {
    if (req == null) {
      throw new ApiBadRequestException("request body is required");
    }
    requireNonBlank(req.kind(), "kind");
    requireNonBlank(req.scope(), "scope");
    requireNonBlank(req.key(), "key");
    requireNonBlank(req.plaintext(), "plaintext");
    try {
      Credential created =
          service.create(
              new NewCredentialRequest(req.kind(), req.scope(), req.key(), req.plaintext()));
      return Response.status(201).entity(CredentialDto.from(created)).build();
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException(e.getMessage());
    }
  }

  // ── PUT /api/v1/credentials/{id} ───────────────────────────────────────────

  @PUT
  @Path("/{id}")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.MANAGE_CREDENTIALS, Roles.EDIT_PIPELINE, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public CredentialDto update(@PathParam("id") String idStr, UpdateCredentialRequest req) {
    long id = JobsApi.parseLong(idStr, "id");
    if (req == null) {
      throw new ApiBadRequestException("request body is required");
    }
    requireNonBlank(req.kind(), "kind");
    requireNonBlank(req.plaintext(), "plaintext");
    try {
      Credential updated = service.update(id, new CredentialUpdate(req.kind(), req.plaintext()));
      return CredentialDto.from(updated);
    } catch (CredentialNotFoundException e) {
      throw new ApiNotFoundException(e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException(e.getMessage());
    }
  }

  // ── DELETE /api/v1/credentials/{id} ────────────────────────────────────────

  @DELETE
  @Path("/{id}")
  @RolesAllowed({Roles.MANAGE_CREDENTIALS, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response delete(@PathParam("id") String idStr) {
    long id = JobsApi.parseLong(idStr, "id");
    service.delete(id);
    return Response.noContent().build();
  }

  // ── POST /api/v1/credentials/rotate-kek ────────────────────────────────────

  /**
   * KEK rotation. Triggers a batch re-wrap of every stored DEK under the currently-active KEK
   * (whatever {@code CredentialKeyProvider.active()} reports). The secret payloads are not
   * decrypted or re-encrypted; only the wrapped-DEK column is touched per row. Idempotent — calling
   * this twice after a single key-rotation event is a no-op on the second pass.
   *
   * <p>Returns {@code { "backend": "...", "rewrapped": &lt;n&gt; }}. {@code rewrapped} is {@code 0}
   * for backends that delegate rotation to an upstream provider (Vault, AWS Secrets Manager).
   */
  @POST
  @Path("/rotate-kek")
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.MANAGE_CREDENTIALS, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response rotateKek() {
    int rewrapped = service.rotateKek();
    return Response.ok(new RotateKekResponse(service.backendName(), rewrapped)).build();
  }

  /** Response body for the rotation endpoint. */
  public record RotateKekResponse(String backend, int rewrapped) {}

  // ── helpers ────────────────────────────────────────────────────────────────

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new ApiBadRequestException("field '" + field + "' is required");
    }
  }
}
