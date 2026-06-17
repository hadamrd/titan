package io.adaptiq.titan.notifications;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;

/**
 * Minimal SMTP submission contract — one method, one envelope. Kept as an interface so the
 * production socket-based implementation ({@link SocketSmtpSender}) and the test in-memory fakes
 * used by the unit + integration suites are interchangeable via CDI.
 *
 * <p>Implementations must:
 *
 * <ul>
 *   <li>Send a single multipart-or-html message to one or more {@code rcpt} addresses in one SMTP
 *       session (one MAIL FROM, multiple RCPT TOs, one DATA).
 *   <li>If {@code username} is non-null, perform {@code AUTH PLAIN} after EHLO. Plaintext-only
 *       SMTP-auth is acceptable — the cron job pulls the credential out of {@code
 *       CredentialsService} just before sending, which is the encrypted-at-rest contract Titan
 *       cares about.
 *   <li>Throw on any non-2xx SMTP response so the digest job surfaces the failure to logs +
 *       observability rather than silently dropping mail.
 * </ul>
 */
public interface SmtpSender {

  void send(
      @NonNull String host,
      int port,
      @Nullable String username,
      @Nullable String password,
      @NonNull String from,
      @NonNull List<String> recipients,
      @NonNull String subject,
      @NonNull String htmlBody)
      throws SmtpException;

  /** Thrown for any protocol failure — wrap I/O and bad-response together. */
  class SmtpException extends Exception {
    private static final long serialVersionUID = 1L;

    public SmtpException(String message) {
      super(message);
    }

    public SmtpException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
