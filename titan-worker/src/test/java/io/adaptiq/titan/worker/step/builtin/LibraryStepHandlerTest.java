package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link LibraryStepHandler} ({@code libraryCall}) — TCK + design/53 dispatch behaviour. */
class LibraryStepHandlerTest extends StepHandlerTck {

  @TempDir Path libsHome; // the LibraryFetcher cache root

  @TempDir Path libRepo; // where the test library git repo is built

  private String coordinate;

  private static final String GIT_LIB =
      """
        def clone(Map args) {
          echo("cloning " + args.url)
          setOutput("cloned", args.url)
        }
        def tag(Map args) {
          echo("tagging " + args.name)
          setOutput("tagged", args.name)
        }
        """;

  private static final String DEPLOY_LIB =
      """
        def call(Map args) {
          echo("deploying " + args.service)
          setOutput("deployedSha", "abc123")
        }
        """;

  @BeforeEach
  void buildLibrary() throws Exception {
    coordinate =
        GitLibraryFixture.build(
            libRepo,
            null,
            Map.of(
                "git.groovy", GIT_LIB,
                "deploy.groovy", DEPLOY_LIB));
  }

  @Override
  protected StepHandler newHandler() {
    return new LibraryStepHandler(libsHome);
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of(
        "library",
        coordinate,
        "file",
        "deploy",
        "method",
        "call",
        "args",
        Map.of("service", "billing"));
  }

  @Test
  void callsTheNamedMethodOnAVarsFile() throws Exception {
    StepHandlerTck.CapturingOutputs outputs = new StepHandlerTck.CapturingOutputs();
    StepResult result =
        newHandler()
            .execute(
                requestWithOutputs(
                    Map.of(
                        "library",
                        coordinate,
                        "file",
                        "git",
                        "method",
                        "clone",
                        "args",
                        Map.of("url", "https://example.com/x.git")),
                    outputs));
    assertTrue(result.isSuccess(), result.message());
    assertEquals("https://example.com/x.git", outputs.map.get("cloned"));
  }

  @Test
  void multipleMethodsOnTheSameFileWork() throws Exception {
    StepHandlerTck.CapturingOutputs outputs = new StepHandlerTck.CapturingOutputs();
    StepResult result =
        newHandler()
            .execute(
                requestWithOutputs(
                    Map.of(
                        "library",
                        coordinate,
                        "file",
                        "git",
                        "method",
                        "tag",
                        "args",
                        Map.of("name", "v1.2.3")),
                    outputs));
    assertTrue(result.isSuccess(), result.message());
    assertEquals("v1.2.3", outputs.map.get("tagged"));
  }

  @Test
  void anUnknownMethodFailsLoudNamingTheFile() throws Exception {
    StepResult result =
        newHandler()
            .execute(
                request(
                    Map.of(
                        "library",
                        coordinate,
                        "file",
                        "git",
                        "method",
                        "rebase",
                        "args",
                        Map.of())));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("rebase"), result.message());
    assertTrue(result.message().contains("git.groovy"), result.message());
  }

  @Test
  void anUnknownFileFailsLoud() throws Exception {
    StepResult result =
        newHandler()
            .execute(
                request(
                    Map.of(
                        "library",
                        coordinate,
                        "file",
                        "nope",
                        "method",
                        "call",
                        "args",
                        Map.of())));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("nope"), result.message());
  }

  @Test
  void aMissingLibraryArgumentFails() throws Exception {
    StepResult result = newHandler().execute(request(Map.of("file", "deploy", "method", "call")));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("library"), result.message());
  }

  @Test
  void aMissingFileArgumentFails() throws Exception {
    StepResult result =
        newHandler().execute(request(Map.of("library", coordinate, "method", "call")));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("file"), result.message());
  }

  @Test
  void shInsideTheCalledMethodRunsInTheStepWorkspace() throws Exception {
    Path lib2 = Files.createTempDirectory("lib2");
    String fn =
        """
            def call(Map args) {
              sh("echo hi > marker.txt")
            }
            """;
    String coord = GitLibraryFixture.build(lib2, null, Map.of("touchMarker.groovy", fn));
    StepResult result =
        newHandler()
            .execute(
                request(
                    Map.of(
                        "library",
                        coord,
                        "file",
                        "touchMarker",
                        "method",
                        "call",
                        "args",
                        Map.of())));
    assertTrue(result.isSuccess(), result.message());
    assertTrue(Files.exists(workDir.resolve("marker.txt")));
  }

  /** A {@link StepRequest} like {@link #request} but with a caller-supplied output sink. */
  private StepRequest requestWithOutputs(
      Map<String, Object> args, StepHandlerTck.CapturingOutputs outputs) {
    StepHandler handler = newHandler();
    return new StepRequest(
        handler.descriptorId(),
        args,
        workDir,
        Map.of(),
        1L,
        "node-1",
        null,
        newExecutor(),
        new StepHandlerTck.CapturingLog(),
        outputs);
  }
}
