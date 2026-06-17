package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.MaskingLogSink;
import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link EchoStepHandler} — the shared TCK plus {@code echo}-specifics. */
class EchoStepHandlerTest extends StepHandlerTck {

  @Override
  protected StepHandler newHandler() {
    return new EchoStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("message", "hello from echo");
  }

  /** Build a request whose log sink is the given one (for the masking test). */
  private StepRequest request(Map<String, Object> args, LogSink log) {
    return new StepRequest(
        "echo",
        args,
        workDir,
        Map.of(),
        1L,
        "node-1",
        null,
        new LocalProcessExecutor(),
        log,
        new CapturingOutputs());
  }

  @Test
  void writesTheMessageToStdoutNotSystemOrStderr() throws Exception {
    CapturingLog log = new CapturingLog();
    new EchoStepHandler().execute(request(Map.of("message", "building core"), log));
    assertEquals(
        List.of("stdout: building core"),
        log.lines,
        "echo output must land on the stdout stream verbatim");
  }

  @Test
  void splitsAMultiLineMessageIntoSeparateNewlineFreeLines() throws Exception {
    CapturingLog log = new CapturingLog();
    new EchoStepHandler()
        .execute(request(Map.of("message", "line one\nline two\nline three"), log));
    assertEquals(
        List.of("stdout: line one", "stdout: line two", "stdout: line three"),
        log.lines,
        "each \\n must become its own LogSink line (no embedded newlines)");
  }

  @Test
  void preservesUnicodeWhitespaceAndVeryLongContent() throws Exception {
    String long8k = "x".repeat(8192);
    String message = "  indented ✓ café  " + long8k;
    CapturingLog log = new CapturingLog();
    new EchoStepHandler().execute(request(Map.of("message", message), log));
    assertEquals(
        List.of("stdout: " + message),
        log.lines,
        "content must survive verbatim — no trim, no truncation");
  }

  @Test
  void anEmptyMessageEmitsExactlyOneEmptyStdoutLine() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult r = new EchoStepHandler().execute(request(Map.of("message", ""), log));
    assertTrue(r.isSuccess());
    assertEquals(List.of("stdout: "), log.lines);
  }

  @Test
  void aSecretInTheMessageIsMasked() throws Exception {
    CapturingLog underlying = new CapturingLog();
    LogSink masking = MaskingLogSink.wrap(underlying, List.of("s3cr3t-token"));
    new EchoStepHandler()
        .execute(request(Map.of("message", "deploying with s3cr3t-token now"), masking));
    assertFalse(
        underlying.text().contains("s3cr3t-token"),
        "the handler must log through request.log(), so masking applies");
    assertTrue(underlying.text().contains("****"));
  }

  @Test
  void carriageReturnsArePreservedVerbatimNotStripped() throws Exception {
    CapturingLog log = new CapturingLog();
    new EchoStepHandler().execute(request(Map.of("message", "windows\r\nline"), log));
    assertEquals(
        List.of("stdout: windows\r", "stdout: line"),
        log.lines,
        "echo splits on \\n only — a \\r rides through verbatim, never stripped");
  }

  @Test
  void isIdempotentAcrossRerunsOnTheSameInstance() throws Exception {
    EchoStepHandler handler = new EchoStepHandler();
    CapturingLog first = new CapturingLog();
    CapturingLog second = new CapturingLog();
    handler.execute(request(Map.of("message", "twice\nover"), first));
    handler.execute(request(Map.of("message", "twice\nover"), second));
    assertEquals(
        first.lines,
        second.lines,
        "a reused handler instance must produce identical output on a rerun");
    assertEquals(List.of("stdout: twice", "stdout: over"), second.lines);
  }
}
