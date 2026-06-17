package io.adaptiq.titan.worker.step;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link LogSink} decorator that masks credential secrets in a step's log output (design/39 §3,
 * implementing design/32 §12 D6).
 *
 * <p>The controller resolves a step's {@code credentials:} bindings against the credentials store
 * and delivers the resolved secret <em>values</em> in the {@code EXECUTE_COMMAND} payload's {@code
 * maskSecrets} array. The worker wraps that step's {@code DbLogSink} in one of these for the
 * lifetime of the step: every log line has each secret occurrence replaced with {@code ****} before
 * it reaches storage.
 *
 * <p><strong>This is best-effort, not a security boundary</strong> (design/39 §5 / design/26 Tier
 * C). It removes the obvious leak — {@code echo $PASSWORD}, a tool that prints its arguments — but
 * it cannot catch a secret a step transforms (base64-encodes, splits, reverses). Highly-sensitive
 * material should be bound as a {@code file}/{@code sshKey} (a path, never a value), so it is never
 * a candidate to be printed. Longest-secret-first replacement avoids a shorter secret unmasking a
 * fragment of a longer one.
 */
public final class MaskingLogSink implements LogSink {

  /** The replacement text written in place of a secret. */
  public static final String MASK = "****";

  private final LogSink delegate;
  private final List<String> secrets;

  /**
   * Wrap {@code delegate}, masking every value in {@code secrets}. Blank / very short secrets are
   * dropped — masking a 1-character "secret" would redact unrelated text and is never a real
   * credential. A {@code delegate} with no secrets is returned unwrapped by {@link #wrap(LogSink,
   * List)}.
   *
   * @param delegate the underlying sink — typically the step's {@code DbLogSink}
   * @param secrets the secret values to mask
   */
  public MaskingLogSink(LogSink delegate, List<String> secrets) {
    this.delegate = delegate;
    // Longest first so a secret that contains a shorter one is masked whole.
    List<String> filtered = new ArrayList<>();
    if (secrets != null) {
      for (String s : secrets) {
        if (s != null && s.length() >= 3) {
          filtered.add(s);
        }
      }
    }
    filtered.sort((a, b) -> Integer.compare(b.length(), a.length()));
    this.secrets = filtered;
  }

  /**
   * Wrap {@code delegate} only if there is something to mask — otherwise return it unchanged, so a
   * step with no credentials pays nothing.
   */
  public static LogSink wrap(LogSink delegate, List<String> secrets) {
    if (secrets == null || secrets.isEmpty()) {
      return delegate;
    }
    MaskingLogSink masking = new MaskingLogSink(delegate, secrets);
    return masking.secrets.isEmpty() ? delegate : masking;
  }

  @Override
  public void line(String stream, String text) {
    delegate.line(stream, mask(text));
  }

  /** Replace every secret occurrence in {@code text} with {@link #MASK}. */
  String mask(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String masked = text;
    for (String secret : secrets) {
      if (masked.contains(secret)) {
        masked = masked.replace(secret, MASK);
      }
    }
    return masked;
  }
}
