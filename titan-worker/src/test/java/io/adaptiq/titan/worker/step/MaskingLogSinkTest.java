package io.adaptiq.titan.worker.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link MaskingLogSink} — the worker-side credential masking of design/39 §3 (D6). The
 * controller delivers resolved secret values in the {@code EXECUTE_COMMAND} payload; the worker
 * wraps the step's log sink so a secret never reaches storage in cleartext.
 */
class MaskingLogSinkTest {

  /** A {@link LogSink} that records the (already-masked) text it is handed. */
  private static final class Recording implements LogSink {
    final List<String> lines = new ArrayList<>();

    @Override
    public void line(String stream, String text) {
      lines.add(text);
    }
  }

  @Test
  void masksASecretInALogLine() {
    Recording underlying = new Recording();
    LogSink sink = MaskingLogSink.wrap(underlying, List.of("s3cr3t-pw"));

    sink.line("stdout", "logging in with password s3cr3t-pw to registry");

    assertEquals("logging in with password **** to registry", underlying.lines.get(0));
  }

  @Test
  void masksEveryOccurrenceOfASecret() {
    Recording underlying = new Recording();
    LogSink sink = MaskingLogSink.wrap(underlying, List.of("tok123"));

    sink.line("stdout", "tok123 used, then tok123 again");

    assertEquals("**** used, then **** again", underlying.lines.get(0));
    assertFalse(underlying.lines.get(0).contains("tok123"));
  }

  @Test
  void masksMultipleDistinctSecrets() {
    Recording underlying = new Recording();
    LogSink sink = MaskingLogSink.wrap(underlying, List.of("user-pw", "api-token"));

    sink.line("stdout", "pw=user-pw token=api-token");

    assertEquals("pw=**** token=****", underlying.lines.get(0));
  }

  @Test
  void masksALongerSecretEvenWhenItContainsAShorterOne() {
    Recording underlying = new Recording();
    // "abc" is a substring of "abcdef" — longest-first ordering masks the whole long secret.
    LogSink sink = MaskingLogSink.wrap(underlying, List.of("abc", "abcdef"));

    sink.line("stdout", "value is abcdef here");

    assertEquals("value is **** here", underlying.lines.get(0));
  }

  @Test
  void aLineWithNoSecretPassesThroughUnchanged() {
    Recording underlying = new Recording();
    LogSink sink = MaskingLogSink.wrap(underlying, List.of("secret-value"));

    sink.line("stdout", "an ordinary line of build output");

    assertEquals("an ordinary line of build output", underlying.lines.get(0));
  }

  @Test
  void wrapReturnsTheDelegateUnchangedWhenThereAreNoSecrets() {
    Recording underlying = new Recording();
    assertSame(underlying, MaskingLogSink.wrap(underlying, List.of()));
    assertSame(underlying, MaskingLogSink.wrap(underlying, null));
  }

  @Test
  void veryShortSecretsAreNotMaskedToAvoidRedactingUnrelatedText() {
    Recording underlying = new Recording();
    // A 1- or 2-character "secret" is never a real credential; masking it would redact
    // arbitrary text. wrap() drops them, and with no maskable secret returns the delegate.
    assertSame(underlying, MaskingLogSink.wrap(underlying, List.of("a", "xy")));
  }

  @Test
  void systemLinesAreAlsoMasked() {
    Recording underlying = new Recording();
    LogSink sink = MaskingLogSink.wrap(underlying, List.of("leaked-secret"));

    sink.system("worker diagnostic mentioning leaked-secret somehow");

    assertTrue(underlying.lines.get(0).contains("****"));
    assertFalse(underlying.lines.get(0).contains("leaked-secret"));
  }
}
