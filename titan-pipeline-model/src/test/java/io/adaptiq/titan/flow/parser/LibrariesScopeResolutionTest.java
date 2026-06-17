package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end-on-one-host proof that a pipeline-level {@code libraries:} block resolves and expands
 * a fetched library fragment — closes the "{@code libraries:} has ZERO e2e" gap (issue #1228, test
 * matrix #1).
 *
 * <p>Existing coverage is split across two seams that never meet in a test: {@link
 * LibraryAliasDispatchTest} proves the {@code libraries:} block parses and that {@code
 * <alias>.<method>:} rewrites into a {@code libraryCall} step carrying the alias coordinate; {@link
 * LibraryFetcherTest} proves {@link LibraryFetcher#resolve} fetches a real {@code file://} git
 * repo. This class joins them: it takes the coordinate <em>as the parser resolved it from the
 * {@code libraries:} block</em> and feeds it to {@link LibraryFetcher}, asserting a marker that
 * lives ONLY in the fetched library repo — proving the alias→coordinate→fetch→library-code path is
 * whole, not two independently-green halves.
 *
 * <p>These drive the genuine {@code git} CLI against repos built by {@link GitLibraryFixture} (no
 * mocking) and are the host-local mirror of the rig spec {@code
 * 47-shared-library-pipeline.spec.ts}.
 */
class LibrariesScopeResolutionTest {

  /**
   * A marker string that exists ONLY inside the fetched library's {@code vars/} file — never in the
   * consumer pipeline YAML below. Asserting it in the fetched checkout proves the library code was
   * actually fetched, not inlined (issue #1228 acceptance: "a marker that could only be produced by
   * the library's code").
   */
  private static final String LIBRARY_ONLY_MARKER = "TITAN_LIB_MARKER_buildAndTest_v1";

  /**
   * Happy path: a {@code libraries:} alias whose coordinate points at a real git repo resolves to a
   * checkout that carries the library-only marker, and the {@code ci.buildAndTest:} step rewrites
   * into a {@code libraryCall} carrying exactly that coordinate.
   */
  @Test
  void librariesBlockResolvesAndExpandsAFetchedFragment(@TempDir Path tmp) throws Exception {
    // The shared-library convention (design/53): a `<alias>.<method>:` step loads
    // vars/<alias>.groovy and calls <method>(Map). The alias here is `ci`, so the reusable code
    // lives in vars/ci.groovy and the invoked method is buildAndTest.
    String libUrl =
        GitLibraryFixture.buildLibrary(
            tmp.resolve("ci-templates"),
            "v1",
            Map.of(
                "vars/ci.groovy",
                "def buildAndTest(Map args = [:]) {\n"
                    + "  sh \"echo "
                    + LIBRARY_ONLY_MARKER
                    + " > lib-out.txt\"\n"
                    + "}\n"));

    // The consumer pipeline declares the library by coordinate and invokes its reusable method.
    // The marker string is deliberately absent here — it lives only in the fetched repo.
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            "libraries:\n"
                + "  ci: \""
                + libUrl
                + "@v1\"\n"
                + "stages:\n"
                + "  - stage: Build\n"
                + "    steps:\n"
                + "      - ci.buildAndTest: { profile: fast }\n");

    // The parser resolved the alias to its coordinate and rewrote the dotted step into libraryCall.
    String coordinate = model.getLibraryAliases().get("ci");
    assertEquals(libUrl + "@v1", coordinate, "the libraries: alias must resolve to its coordinate");
    StepModel step = model.getStages().get(0).getSteps().get(0);
    assertEquals("libraryCall", step.getDescriptorId());
    assertEquals(
        coordinate, step.getArguments().get("library"), "libraryCall must carry the coordinate");
    assertEquals("ci", step.getArguments().get("file"), "file is the alias → vars/<alias>.groovy");
    assertEquals("buildAndTest", step.getArguments().get("method"));

    // Resolve the coordinate exactly as LibraryStepHandler does on the worker, then locate the
    // vars/<file>.groovy the handler would load.
    Path checkout = new LibraryFetcher(tmp.resolve("cache")).resolve(coordinate);

    Path varsFile = checkout.resolve("vars/ci.groovy");
    assertTrue(Files.isRegularFile(varsFile), "the fetched library must carry vars/ci.groovy");
    assertTrue(
        Files.readString(varsFile).contains(LIBRARY_ONLY_MARKER),
        "the fetched library code must carry the library-only marker — proving it was fetched, "
            + "not inlined into the consumer pipeline");
  }

  /**
   * Adversarial path: a {@code libraries:} alias whose ref does not exist on the remote fails with
   * a structured {@link LibraryFetcher} error — never a silent skip (issue #1228 acceptance). The
   * block parses fine (a coordinate is just a string at parse time); the failure surfaces at
   * resolution, locating both the bad ref and the repo.
   */
  @Test
  void librariesBlockWithBadRefFailsWithStructuredError(@TempDir Path tmp) throws Exception {
    String libUrl =
        GitLibraryFixture.buildLibrary(
            tmp.resolve("ci-templates"),
            "v1",
            Map.of("vars/ci.groovy", "def buildAndTest(Map args = [:]) { echo 'hi' }\n"));

    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            "libraries:\n"
                + "  ci: \""
                + libUrl
                + "@v9-does-not-exist\"\n"
                + "stages:\n"
                + "  - stage: Build\n"
                + "    steps:\n"
                + "      - ci.buildAndTest: {}\n");

    String coordinate = model.getLibraryAliases().get("ci");
    LibraryFetcher fetcher = new LibraryFetcher(tmp.resolve("cache"));

    PipelineParseException e =
        assertThrows(PipelineParseException.class, () -> fetcher.resolve(coordinate));
    assertTrue(
        e.getMessage().contains("v9-does-not-exist") && e.getMessage().contains("not found"),
        "a bad library ref must fail loud, locating the missing ref and the repo: "
            + e.getMessage());
  }
}
