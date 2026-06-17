package io.adaptiq.titan.api.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.api.ApiNotFoundException;
import io.adaptiq.titan.api.dto.ProblemJson;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps {@link ApiNotFoundException} to HTTP 404 with an RFC 7807 {@code application/problem+json}
 * body. Same status code and JSON shape as the former Javalin exception handler in {@code
 * ApiServer}.
 */
@Provider
public class NotFoundExceptionMapper implements ExceptionMapper<ApiNotFoundException> {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PROBLEM_CONTENT_TYPE = "application/problem+json";

  @Override
  public Response toResponse(ApiNotFoundException exception) {
    try {
      String body = MAPPER.writeValueAsString(ProblemJson.notFound(exception.getMessage()));
      return Response.status(404).type(PROBLEM_CONTENT_TYPE).entity(body).build();
    } catch (Exception e) {
      return Response.status(404)
          .type(PROBLEM_CONTENT_TYPE)
          .entity("{\"status\":404,\"title\":\"Not Found\"}")
          .build();
    }
  }
}
