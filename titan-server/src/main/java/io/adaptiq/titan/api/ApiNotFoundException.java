package io.adaptiq.titan.api;

/**
 * Thrown by API handlers when a requested resource does not exist. The global exception handler in
 * {@link ApiServer} maps this to an HTTP 404 with a RFC 7807 {@code problem+json} body.
 */
public final class ApiNotFoundException extends RuntimeException {
  public ApiNotFoundException(String message) {
    super(message);
  }
}
