package io.adaptiq.titan.api.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.dto.ProblemJson;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Catch-all mapper for unhandled {@link Exception}s — maps to HTTP 500 with an RFC 7807 {@code
 * application/problem+json} body. Same status code and JSON shape as the former Javalin catch-all
 * handler in {@code ApiServer}.
 *
 * <p>JAX-RS {@link WebApplicationException} subclasses (including the framework's own {@code
 * NotFoundException}, {@code NotAllowedException}, etc.) are passed through unchanged so Quarkus
 * REST can handle them with their correct status codes.
 */
@Provider
public class GenericExceptionMapper implements ExceptionMapper<Exception> {

  private static final Logger LOGGER = Logger.getLogger(GenericExceptionMapper.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PROBLEM_CONTENT_TYPE = "application/problem+json";

  @Override
  public Response toResponse(Exception exception) {
    // Let JAX-RS WebApplicationExceptions (framework 404/405/etc.) pass through —
    // they already carry the correct HTTP status.
    if (exception instanceof WebApplicationException wae) {
      return wae.getResponse();
    }
    LOGGER.log(Level.SEVERE, "[titan-api] unhandled exception", exception);
    try {
      String body =
          MAPPER.writeValueAsString(
              ProblemJson.internalError("Unexpected error: " + exception.getMessage()));
      return Response.status(500).type(PROBLEM_CONTENT_TYPE).entity(body).build();
    } catch (Exception e) {
      return Response.status(500)
          .type(PROBLEM_CONTENT_TYPE)
          .entity("{\"status\":500,\"title\":\"Internal Server Error\"}")
          .build();
    }
  }
}
