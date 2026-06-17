package io.adaptiq.titan.db;

/**
 * Wraps {@link java.sql.SQLException}s thrown from Titan store methods so callers don't have to
 * handle checked exceptions for what is, in practice, an unrecoverable condition (DB unreachable,
 * schema drift, etc.).
 *
 * <p>Use the message to identify the operation: {@code "jobs.findById failed: 42"}.
 */
public class TitanDataException extends RuntimeException {
  public TitanDataException(String message, Throwable cause) {
    super(message, cause);
  }

  public TitanDataException(String message) {
    super(message);
  }
}
