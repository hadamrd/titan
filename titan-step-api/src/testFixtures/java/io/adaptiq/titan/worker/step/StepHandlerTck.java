package io.adaptiq.titan.worker.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Titan step <strong>Technology Compatibility Kit</strong> — the contract test suite every
 * {@link StepHandler}, built-in or third-party, must pass (design/32 §10).
 *
 * <p>A handler is only a handler if it passes the TCK. Subclass this, supply a fresh handler and a
 * set of arguments that should make it succeed, and the contract is verified: a stable descriptor,
 * a descriptor that agrees with itself, a non-null result, success on valid input. This is the
 * artifact that makes the SPI trustworthy rather than aspirational — Titan's own {@code sh}/{@code
 * script} handlers run against it exactly as a third party's would.
 */
public abstract class StepHandlerTck {

  @TempDir protected Path workDir;

  /** A fresh handler under test. */
  protected abstract StepHandler newHandler();

  /** Arguments that must drive {@link #newHandler()} to {@code SUCCESS}. */
  protected abstract Map<String, Object> validArguments();

  /**
   * A fresh {@link StepExecutor} for the handler under test.
   *
   * <p>The TCK lives in {@code titan-step-api}, which carries no executor implementation — the
   * worker is the only thing that <em>implements</em> the executor (design/42 §4.1, §5.2). A
   * subclass running inside {@code titan-worker} (or a third-party step jar) supplies its real
   * executor here, typically {@code new LocalProcessExecutor()}.
   */
  protected abstract StepExecutor newExecutor();

  /** Build a {@link StepRequest} for this handler with the given arguments + capturing sinks. */
  protected StepRequest request(Map<String, Object> arguments) {
    StepHandler handler = newHandler();
    return new StepRequest(
        handler.descriptorId(),
        arguments,
        workDir,
        Map.of(),
        1L,
        "node-1",
        null,
        newExecutor(),
        new CapturingLog(),
        new CapturingOutputs(),
        new CapturingArtifacts(),
        new CapturingTestReports());
  }

  // ── the contract ──────────────────────────────────────────────────────

  @Test
  void descriptorIdIsNonBlank() {
    assertFalse(newHandler().descriptorId().isBlank(), "descriptorId must be non-blank");
  }

  @Test
  void descriptorAgreesWithDescriptorId() {
    StepHandler handler = newHandler();
    assertEquals(
        handler.descriptorId(),
        handler.descriptor().descriptorId(),
        "the handler and its descriptor must agree on the id");
  }

  @Test
  void descriptorCarriesDisplayNameAndHelp() {
    StepDescriptor descriptor = newHandler().descriptor();
    assertNotNull(descriptor.displayName());
    assertFalse(descriptor.displayName().isBlank(), "descriptor needs a display name");
    assertNotNull(descriptor.help());
    assertFalse(descriptor.help().isBlank(), "descriptor needs help text");
  }

  @Test
  void executeNeverReturnsNull() throws Exception {
    StepResult result = newHandler().execute(request(validArguments()));
    assertNotNull(result, "execute must never return null");
    assertNotNull(result.status(), "a result must carry a status");
  }

  @Test
  void executeOnValidArgumentsSucceeds() throws Exception {
    StepResult result = newHandler().execute(request(validArguments()));
    assertTrue(
        result.isSuccess(),
        "the handler must succeed on its declared valid arguments: " + result.message());
  }

  // ── capturing sinks for handler tests ─────────────────────────────────

  /** A thread-safe {@link LogSink} that records every line (executors pump from two threads). */
  public static final class CapturingLog implements LogSink {
    public final List<String> lines = new CopyOnWriteArrayList<>();

    @Override
    public void line(String stream, String text) {
      lines.add(stream + ": " + text);
    }

    public String text() {
      return String.join("\n", lines);
    }
  }

  /** An {@link OutputSink} that records published outputs into a map. */
  public static final class CapturingOutputs implements OutputSink {
    public final Map<String, Object> map = new ConcurrentHashMap<>();

    @Override
    public void put(String key, Object value) {
      map.put(key, value);
    }
  }

  /**
   * An {@link ArtifactSink} that records every archived file — its name, its bytes, and whether
   * fingerprinting was requested — so a handler that archives can be exercised without a real
   * {@code ArtifactStore}.
   */
  public static final class CapturingArtifacts implements ArtifactSink {

    /** One archived file captured by the sink. */
    public record Archived(String name, byte[] content, boolean fingerprint) {}

    public final List<Archived> archived = new CopyOnWriteArrayList<>();

    @Override
    public void archive(String name, Path file, boolean fingerprint) throws IOException {
      archived.add(new Archived(name, Files.readAllBytes(file), fingerprint));
    }
  }

  /**
   * A {@link TestResultSink} that records every {@link TestResultSink.Case} batch submitted — so a
   * handler that publishes parsed JUnit cases (the {@code junit} step) can be exercised without a
   * real {@code titan.test_result} table. The flattened {@link #cases} list is the convenient read
   * for "everything submitted across calls"; {@link #submissions} preserves call boundaries for
   * tests that need to assert {@code submit(...)} was called exactly once with N rows.
   */
  public static final class CapturingTestReports implements TestResultSink {
    public final List<List<Case>> submissions = new CopyOnWriteArrayList<>();
    public final List<Case> cases = new CopyOnWriteArrayList<>();

    @Override
    public void submit(List<Case> batch) {
      submissions.add(List.copyOf(batch));
      cases.addAll(batch);
    }
  }
}
