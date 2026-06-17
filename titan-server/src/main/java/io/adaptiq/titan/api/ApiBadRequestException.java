package io.adaptiq.titan.api;

/**
 * Thrown by API handlers when the request is malformed (bad path parameter, invalid body, etc.).
 * The global exception handler in {@link ApiServer} maps this to HTTP 400 with a RFC 7807 {@code
 * problem+json} body.
 */
public final class ApiBadRequestException extends RuntimeException {
  public ApiBadRequestException(String message) {
    super(message);
  }
}
