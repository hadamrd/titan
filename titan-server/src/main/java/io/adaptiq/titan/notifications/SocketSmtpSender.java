package io.adaptiq.titan.notifications;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * RFC-5321 SMTP submission over a plain TCP socket. Deliberately avoids dragging in {@code
 * jakarta.mail} for a single email path: the digest send is one message at a time, low frequency,
 * and the protocol surface we need is small (EHLO / AUTH PLAIN / MAIL FROM / RCPT TO / DATA /
 * QUIT).
 *
 * <p>This is NOT a general-purpose SMTP client — no STARTTLS upgrade, no DSN, no chunking. It is
 * what the digest job needs: speak to {@code smtp:25} or a submission relay configured by the
 * operator, with optional AUTH PLAIN. Production deployments behind an SMTP submission relay (the
 * common SRE pattern) get encryption at the relay's outbound hop.
 *
 * <p>The {@link ApplicationScoped} CDI bean lets {@link DailyDigestJob} inject it; tests that need
 * a fake replace it with {@code @Alternative}.
 */
@ApplicationScoped
public class SocketSmtpSender implements SmtpSender {

  /** Conservative socket / response timeout (15s) — avoids hanging a scheduler thread forever. */
  private static final int SOCKET_TIMEOUT_MS = 15_000;

  private static final DateTimeFormatter RFC_5322 =
      DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z");

  @Override
  public void send(
      @NonNull String host,
      int port,
      @Nullable String username,
      @Nullable String password,
      @NonNull String from,
      @NonNull List<String> recipients,
      @NonNull String subject,
      @NonNull String htmlBody)
      throws SmtpException {
    if (recipients.isEmpty()) {
      throw new SmtpException("recipients list is empty");
    }
    validateAddress(from);
    for (String r : recipients) {
      validateAddress(r);
    }

    try (Socket socket = new Socket(host, port)) {
      socket.setSoTimeout(SOCKET_TIMEOUT_MS);
      BufferedReader in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
      OutputStream out = socket.getOutputStream();

      expect(in, 220, "greeting");
      send(out, "EHLO titan-server");
      drainMultiline(in, 250, "EHLO");

      if (username != null && password != null) {
        send(out, "AUTH PLAIN " + authPlain(username, password));
        expect(in, 235, "AUTH PLAIN");
      }

      send(out, "MAIL FROM:<" + from + ">");
      expect(in, 250, "MAIL FROM");

      for (String r : recipients) {
        send(out, "RCPT TO:<" + r + ">");
        expect(in, 250, "RCPT TO " + r);
      }

      send(out, "DATA");
      expect(in, 354, "DATA");

      writeMessage(out, from, recipients, subject, htmlBody);
      expect(in, 250, "message body");

      send(out, "QUIT");
      // 221 expected but tolerate close
      try {
        expect(in, 221, "QUIT");
      } catch (SmtpException ignored) {
        // some servers drop the connection without 221
      }
    } catch (IOException e) {
      throw new SmtpException("SMTP I/O failure to " + host + ":" + port, e);
    }
  }

  private static void writeMessage(
      OutputStream out, String from, List<String> recipients, String subject, String htmlBody)
      throws IOException {
    StringBuilder headers = new StringBuilder();
    headers.append("From: ").append(from).append("\r\n");
    headers.append("To: ").append(String.join(", ", recipients)).append("\r\n");
    headers.append("Subject: ").append(encodeSubject(subject)).append("\r\n");
    headers
        .append("Date: ")
        .append(RFC_5322.format(OffsetDateTime.now(ZoneOffset.UTC)))
        .append("\r\n");
    headers.append("Message-ID: <").append(UUID.randomUUID()).append("@titan-server>\r\n");
    headers.append("MIME-Version: 1.0\r\n");
    headers.append("Content-Type: text/html; charset=utf-8\r\n");
    headers.append("Content-Transfer-Encoding: 8bit\r\n");
    headers.append("\r\n");

    out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
    // Per RFC-5321 §4.5.2 — a line containing only "." terminates DATA, so any user-content
    // line beginning with "." must be dot-stuffed by prefixing an extra ".".
    for (String line : htmlBody.split("\n", -1)) {
      String stripped = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
      if (stripped.startsWith(".")) {
        out.write((byte) '.');
      }
      out.write(stripped.getBytes(StandardCharsets.UTF_8));
      out.write('\r');
      out.write('\n');
    }
    out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  private static String encodeSubject(String subject) {
    boolean ascii = true;
    for (int i = 0; i < subject.length(); i++) {
      if (subject.charAt(i) > 0x7e) {
        ascii = false;
        break;
      }
    }
    if (ascii) {
      return subject;
    }
    return "=?UTF-8?B?"
        + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8))
        + "?=";
  }

  private static String authPlain(String user, String pass) {
    byte[] payload = ("\0" + user + "\0" + pass).getBytes(StandardCharsets.UTF_8);
    return Base64.getEncoder().encodeToString(payload);
  }

  private static void send(OutputStream out, String line) throws IOException {
    out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
  }

  private static void expect(BufferedReader in, int code, String label)
      throws IOException, SmtpException {
    String resp = in.readLine();
    if (resp == null) {
      throw new SmtpException("SMTP server closed during " + label);
    }
    if (resp.length() < 3) {
      throw new SmtpException("malformed SMTP response at " + label + ": " + resp);
    }
    int got = parseCode(resp, label);
    if (got != code) {
      throw new SmtpException("expected " + code + " at " + label + ", got: " + resp);
    }
  }

  /** Drain a multiline 250- … 250 EHLO response (the server may list many capabilities). */
  private static void drainMultiline(BufferedReader in, int code, String label)
      throws IOException, SmtpException {
    while (true) {
      String resp = in.readLine();
      if (resp == null) {
        throw new SmtpException("SMTP server closed during " + label);
      }
      int got = parseCode(resp, label);
      if (got != code) {
        throw new SmtpException("expected " + code + " at " + label + ", got: " + resp);
      }
      // "250-…" = continuation, "250 …" = final line.
      if (resp.length() < 4 || resp.charAt(3) == ' ') {
        return;
      }
    }
  }

  private static int parseCode(String resp, String label) throws SmtpException {
    try {
      return Integer.parseInt(resp.substring(0, 3));
    } catch (NumberFormatException e) {
      throw new SmtpException("non-numeric SMTP code at " + label + ": " + resp);
    }
  }

  /**
   * Reject control characters in addresses outright — they would otherwise let a caller smuggle an
   * extra command into the SMTP stream by stuffing CR/LF into the local-part or the domain. This is
   * a defence-in-depth check; the caller controls the addresses (workspace config), but the SMTP
   * wire format demands clean values.
   */
  private static void validateAddress(String addr) throws SmtpException {
    if (addr == null || addr.isBlank()) {
      throw new SmtpException("blank address");
    }
    for (int i = 0; i < addr.length(); i++) {
      char c = addr.charAt(i);
      if (c == '\r' || c == '\n' || c == '<' || c == '>' || c < 0x20) {
        throw new SmtpException("illegal character in address: " + addr);
      }
    }
    if (addr.indexOf('@') <= 0 || addr.indexOf('@') == addr.length() - 1) {
      throw new SmtpException("address missing @-separator: " + addr);
    }
  }
}
