package io.adaptiq.titan.api.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.ApiBadRequestException;
import io.adaptiq.titan.api.dto.ProblemJson;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link ApiBadRequestException} to HTTP 400 with an RFC 7807 {@code application/problem+json}
 * body. Same status code and JSON shape as the former Javalin exception handler in {@code
 * ApiServer}.
 */
@Provider
public class BadRequestExceptionMapper implements ExceptionMapper<ApiBadRequestException> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PROBLEM_CONTENT_TYPE = "application/problem+json";

  @Override
  public Response toResponse(ApiBadRequestException exception) {
    try {
      String body = MAPPER.writeValueAsString(ProblemJson.badRequest(exception.getMessage()));
      return Response.status(400).type(PROBLEM_CONTENT_TYPE).entity(body).build();
    } catch (Exception e) {
      return Response.status(400)
          .type(PROBLEM_CONTENT_TYPE)
          .entity("{\"status\":400,\"title\":\"Bad Request\"}")
          .build();
    }
  }
}
