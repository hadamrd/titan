package io.adaptiq.titan.api.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.dto.ProblemJson;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps Quarkus security {@link io.quarkus.security.ForbiddenException} (raised by
 * {@code @RolesAllowed} when the caller's resolved roles don't intersect the declared set) to HTTP
 * 403 with an RFC 7807 {@code application/problem+json} body.
 *
 * <p>Without this mapper Quarkus emits a bare 403 with empty body / no content-type, which breaks
 * the security contract clients depend on (see {@code PatBearerScopesQuarkusTest}: a scoped PAT
 * denied {@code TRIGGER_BUILD} must surface as problem+json, not a silent 403).
 *
 * <p>We target the {@code io.quarkus.security.ForbiddenException} type (NOT the JAX-RS {@code
 * jakarta.ws.rs.ForbiddenException}) because the Quarkus security pipeline throws the former from
 * {@code @RolesAllowed} interceptors.
 */
@Provider
public class ForbiddenExceptionMapper
    implements ExceptionMapper<io.quarkus.security.ForbiddenException> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PROBLEM_CONTENT_TYPE = "application/problem+json";

  @Override
  public Response toResponse(io.quarkus.security.ForbiddenException exception) {
    String detail = exception.getMessage() != null ? exception.getMessage() : "Forbidden";
    try {
      String body = MAPPER.writeValueAsString(ProblemJson.of(403, "Forbidden", detail));
      return Response.status(403).type(PROBLEM_CONTENT_TYPE).entity(body).build();
    } catch (Exception e) {
      return Response.status(403)
          .type(PROBLEM_CONTENT_TYPE)
          .entity("{\"status\":403,\"title\":\"Forbidden\"}")
          .build();
    }
  }
}
