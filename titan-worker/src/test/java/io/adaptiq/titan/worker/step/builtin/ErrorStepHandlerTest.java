package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.MaskingLogSink;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ErrorStepHandler}. Cannot extend {@link StepHandlerTck}: the TCK requires success on valid
 * arguments and an {@code error} step fails by definition. The descriptor-contract checks are
 * replicated inline instead.
 */
class ErrorStepHandlerTest {

  @TempDir Path workDir;

  private StepRequest request(Map<String, Object> args) {
    return request(args, new StepHandlerTck.CapturingLog());
  }

  private StepRequest request(Map<String, Object> args, LogSink log) {
    return new StepRequest(
        "error",
        args,
        workDir,
        Map.of(),
        1L,
        "node-1",
        null,
        new LocalProcessExecutor(),
        log,
        new StepHandlerTck.CapturingOutputs());
  }

  // ── descriptor contract (replicated from the TCK) ──
  @Test
  void descriptorIdIsNonBlank() {
    assertFalse(new ErrorStepHandler().descriptorId().isBlank());
  }

  @Test
  void descriptorAgreesWithDescriptorId() {
    ErrorStepHandler h = new ErrorStepHandler();
    assertEquals(h.descriptorId(), h.descriptor().descriptorId());
  }

  @Test
  void descriptorCarriesDisplayNameAndHelp() {
    var d = new ErrorStepHandler().descriptor();
    assertNotNull(d.displayName());
    assertFalse(d.displayName().isBlank());
    assertNotNull(d.help());
    assertFalse(d.help().isBlank());
  }

  // ── behaviour ──
  @Test
  void failsWithTheExactMessageGiven() throws Exception {
    String msg = "Version tag missing — aborting release.";
    StepResult r = new ErrorStepHandler().execute(request(Map.of("message", msg)));
    assertEquals(StepResult.Status.FAILED, r.status());
    assertEquals(msg, r.message(), "the failure message must survive byte-for-byte");
  }

  @Test
  void returnsAResultRatherThanThrowing() {
    // The queue maps a thrown handler to FAILED, but `error` must fail cleanly, not throw.
    try {
      StepResult r = new ErrorStepHandler().execute(request(Map.of("message", "boom")));
      assertEquals(StepResult.Status.FAILED, r.status());
    } catch (Throwable t) {
      throw new AssertionError("error must not throw, it must return FAILED", t);
    }
  }

  @Test
  void aBlankMessageStillFailsWithAClearReason() throws Exception {
    for (String blank : List.of("", "   ")) {
      StepResult r = new ErrorStepHandler().execute(request(Map.of("message", blank)));
      assertEquals(StepResult.Status.FAILED, r.status());
      assertTrue(
          r.message().contains("no message"),
          "a blank message must yield a clear reason, not an empty/NPE one: " + r.message());
    }
  }

  @Test
  void aSecretInTheMessageIsMasked() throws Exception {
    var underlying = new StepHandlerTck.CapturingLog();
    LogSink masking = MaskingLogSink.wrap(underlying, List.of("s3cr3t-token"));
    new ErrorStepHandler()
        .execute(request(Map.of("message", "auth failed for s3cr3t-token"), masking));
    assertFalse(
        underlying.text().contains("s3cr3t-token"),
        "the handler must log through request.log(), so masking applies");
    assertTrue(underlying.text().contains("****"));
  }

  @Test
  void isIdempotentAcrossReruns() throws Exception {
    StepRequest req = request(Map.of("message", "same failure"));
    StepResult first = new ErrorStepHandler().execute(req);
    StepResult second = new ErrorStepHandler().execute(req);
    assertEquals(first.status(), second.status());
    assertEquals(first.message(), second.message());
  }
}
