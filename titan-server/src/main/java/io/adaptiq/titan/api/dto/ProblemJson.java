package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RFC 7807 "problem+json" error envelope.
 *
 * <p>Every API error — 404, 409, 500 — is serialized as this record with {@code Content-Type:
 * application/problem+json}. Null optional fields are omitted from the JSON payload ({@link
 * JsonInclude.Include#NON_NULL}).
 *
 * @param type a URI reference that identifies the problem type (may be "about:blank" for generic
 *     errors)
 * @param title a short, human-readable summary of the problem type
 * @param status the HTTP status code for this occurrence
 * @param detail a human-readable explanation specific to this occurrence
 * @param instance a URI reference that identifies the specific occurrence (optional)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProblemJson(String type, String title, int status, String detail, String instance) {

  /** Construct a generic problem with {@code type = "about:blank"} and no instance. */
  public static ProblemJson of(int status, String title, String detail) {
    return new ProblemJson("about:blank", title, status, detail, null);
  }

  /** Shorthand for a 404 Not Found problem. */
  public static ProblemJson notFound(String detail) {
    return of(404, "Not Found", detail);
  }

  /** Shorthand for a 400 Bad Request problem. */
  public static ProblemJson badRequest(String detail) {
    return of(400, "Bad Request", detail);
  }

  /** Shorthand for a 500 Internal Server Error problem. */
  public static ProblemJson internalError(String detail) {
    return of(500, "Internal Server Error", detail);
  }
}
