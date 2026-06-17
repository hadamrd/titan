package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.ArtifactSink;
import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ArchiveArtifactsStepHandler} — the shared TCK plus {@code archiveArtifacts}-specifics: the
 * Ant glob, includes/excludes, the empty-match policy, and fingerprint propagation (32E-3).
 */
class ArchiveArtifactsStepHandlerTest extends StepHandlerTck {

  @Override
  protected StepHandler newHandler() {
    return new ArchiveArtifactsStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    // The TCK workspace is empty; an empty match with allowEmptyArchive is the valid success.
    return Map.of("artifacts", "**/*", "allowEmptyArchive", true);
  }

  // ── helpers ───────────────────────────────────────────────────────────

  private void write(String relativePath, String content) throws IOException {
    var target = workDir.resolve(relativePath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, content, StandardCharsets.UTF_8);
  }

  private StepResult run(Map<String, Object> args, StepHandlerTck.CapturingArtifacts sink)
      throws Exception {
    StepRequest request =
        new StepRequest(
            "archiveArtifacts",
            args,
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            new StepHandlerTck.CapturingLog(),
            new StepHandlerTck.CapturingOutputs(),
            sink);
    return new ArchiveArtifactsStepHandler().execute(request);
  }

  // ── behaviour ─────────────────────────────────────────────────────────

  @Test
  void archivesOnlyTheFilesMatchingTheGlob() throws Exception {
    write("target/app.jar", "JAR-A");
    write("target/lib.jar", "JAR-B");
    write("target/notes.txt", "ignore me");
    StepHandlerTck.CapturingArtifacts sink = new StepHandlerTck.CapturingArtifacts();

    StepResult result = run(Map.of("artifacts", "target/*.jar"), sink);

    assertTrue(result.isSuccess(), result.message());
    assertEquals(2, sink.archived.size());
    List<String> names = sink.archived.stream().map(a -> a.name()).sorted().toList();
    assertEquals(List.of("target/app.jar", "target/lib.jar"), names);
  }

  @Test
  void archivedContentIsTheFileBytes() throws Exception {
    write("out/report.html", "<html>built</html>");
    StepHandlerTck.CapturingArtifacts sink = new StepHandlerTck.CapturingArtifacts();

    run(Map.of("artifacts", "out/report.html"), sink);

    assertEquals(
        "<html>built</html>", new String(sink.archived.get(0).content(), StandardCharsets.UTF_8));
  }

  @Test
  void nestedMatchesAreNamedWithForwardSlashes() throws Exception {
    write("a/b/c/deep.txt", "x");
    StepHandlerTck.CapturingArtifacts sink = new StepHandlerTck.CapturingArtifacts();

    run(Map.of("artifacts", "**/*.txt"), sink);

    assertEquals("a/b/c/deep.txt", sink.archived.get(0).name());
  }

  @Test
  void excludesAreHonoured() throws Exception {
    write("reports/public.txt", "ok");
    write("reports/secret.txt", "sshh");
    StepHandlerTck.CapturingArtifacts sink = new StepHandlerTck.CapturingArtifacts();

    run(Map.of("artifacts", "reports/**", "excludes", "**/secret.txt"), sink);

    assertEquals(1, sink.archived.size());
    assertEquals("reports/public.txt", sink.archived.get(0).name());
  }

  @Test
  void noMatchFailsByDefault() throws Exception {
    StepHandlerTck.CapturingArtifacts sink = new StepHandlerTck.CapturingArtifacts();

    StepResult result = run(Map.of("artifacts", "target/*.nonesuch"), sink);

    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("nonesuch"), result.message());
    assertTrue(sink.archived.isEmpty());
  }

  @Test
  void noMatchSucceedsWhenAllowEmptyArchiveIsSet() throws Exception {
    StepHandlerTck.CapturingArtifacts sink = new StepHandlerTck.CapturingArtifacts();

    StepResult result =
        run(Map.of("artifacts", "target/*.nonesuch", "allowEmptyArchive", true), sink);

    assertTrue(result.isSuccess(), result.message());
    assertTrue(sink.archived.isEmpty());
  }

  @Test
  void theFingerprintFlagIsPropagatedToTheSink() throws Exception {
    write("dist/app.tar", "payload");
    StepHandlerTck.CapturingArtifacts withFp = new StepHandlerTck.CapturingArtifacts();
    StepHandlerTck.CapturingArtifacts withoutFp = new StepHandlerTck.CapturingArtifacts();

    run(Map.of("artifacts", "dist/app.tar", "fingerprint", true), withFp);
    run(Map.of("artifacts", "dist/app.tar"), withoutFp);

    assertTrue(withFp.archived.get(0).fingerprint(), "fingerprint:true must reach the sink");
    assertFalse(withoutFp.archived.get(0).fingerprint(), "the default is no fingerprint");
  }

  @Test
  void acceptsTheScalarShorthandGlob() throws Exception {
    // `archiveArtifacts: 'build/*.jar'` — the parser stores the scalar under `value`.
    write("build/app.jar", "shorthand");
    StepHandlerTck.CapturingArtifacts sink = new StepHandlerTck.CapturingArtifacts();

    StepResult result = run(Map.of("value", "build/*.jar"), sink);

    assertTrue(result.isSuccess(), result.message());
    assertEquals(1, sink.archived.size());
    assertEquals("build/app.jar", sink.archived.get(0).name());
  }

  @Test
  void aMissingArtifactsArgumentFails() throws Exception {
    StepResult result = run(Map.of(), new StepHandlerTck.CapturingArtifacts());
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("artifacts"), result.message());
  }

  /**
   * Closes #848 — when a worker has no artifact store wired ({@link ArtifactSink#UNCONFIGURED}),
   * the handler must surface the underlying IOException message in BOTH the streamed step log AND
   * the step's terminal result, rather than letting it bubble up as a generic "step threw" line.
   */
  @Test
  void unconfiguredSinkProducesInformativeFailureAndLogLine() throws Exception {
    write("out.txt", "hello");
    StepHandlerTck.CapturingLog log = new StepHandlerTck.CapturingLog();
    StepRequest request =
        new StepRequest(
            "archiveArtifacts",
            Map.of("artifacts", "out.txt"),
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            log,
            new StepHandlerTck.CapturingOutputs(),
            ArtifactSink.UNCONFIGURED);

    StepResult result = new ArchiveArtifactsStepHandler().execute(request);

    assertFalse(result.isSuccess(), "must fail when the sink is unconfigured");
    assertTrue(
        result.message().contains("out.txt"),
        "terminal message must name the file: " + result.message());
    assertTrue(
        result.message().contains("no artifact store is configured"),
        "terminal message must include the sink's underlying reason: " + result.message());
    assertTrue(
        log.text().contains("no artifact store is configured"),
        "underlying reason must also appear in the streamed step log: " + log.text());
  }
}
