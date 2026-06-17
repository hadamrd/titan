package io.adaptiq.titan.flow.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.flow.model.PipelineModel;
import io.adaptiq.titan.flow.model.StepModel;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The design/53 dotted-step dispatch — {@code libraries:} as an alias map rewrites {@code
 * <alias>.<method>:} step types into {@code libraryCall} with synthesized args.
 */
class LibraryAliasDispatchTest {

  @Test
  void parsesLibrariesAsAnAliasMap() {
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
            libraries:
              acme: "https://github.com/acme/ci.git@v1.0"
              k8s:  "https://github.com/acme/k8s.git@v2.0"
            stages:
              - stage: build
                steps:
                  - sh: echo hi
            """);
    assertEquals(2, model.getLibraryAliases().size());
    assertEquals("https://github.com/acme/ci.git@v1.0", model.getLibraryAliases().get("acme"));
    assertEquals("https://github.com/acme/k8s.git@v2.0", model.getLibraryAliases().get("k8s"));
  }

  @Test
  void rejectsLegacyListFormWithLoudError() {
    // Arbitrary-code synthesis was removed; the list-form `libraries: [coord, ...]` fed it
    // and is no longer supported. Use the alias map.
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parseAndValidate(
                    """
                    libraries:
                      - "https://github.com/acme/ci.git@v1.0"
                    stages:
                      - stage: build
                        steps:
                          - sh: echo hi
                    """));
    assertTrue(e.getMessage().toLowerCase().contains("map"), e.getMessage());
  }

  @Test
  void rewritesAliasDotMethodIntoLibraryCall() {
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
            libraries:
              git: "https://github.com/acme/git.git@v1.0"
            stages:
              - stage: build
                steps:
                  - git.clone: { url: "https://example.com/x.git", branch: main }
            """);
    StepModel step = model.getStages().get(0).getSteps().get(0);
    assertEquals("libraryCall", step.getDescriptorId());
    Map<String, Object> args = step.getArguments();
    assertEquals("https://github.com/acme/git.git@v1.0", args.get("library"));
    assertEquals("git", args.get("file"));
    assertEquals("clone", args.get("method"));
    @SuppressWarnings("unchecked")
    Map<String, Object> userArgs = (Map<String, Object>) args.get("args");
    assertEquals("https://example.com/x.git", userArgs.get("url"));
    assertEquals("main", userArgs.get("branch"));
  }

  @Test
  void bareAliasWithoutDotIsNotIntercepted() {
    // Safety: a declared alias must NEVER shadow a built-in step type. Bare `sh:` works as
    // the built-in even if an alias `sh` exists.
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
            libraries:
              sh: "https://github.com/acme/sh.git@v1.0"
            stages:
              - stage: build
                steps:
                  - sh: echo hi
            """);
    StepModel step = model.getStages().get(0).getSteps().get(0);
    assertEquals("sh", step.getDescriptorId(), "bare alias never intercepts a built-in step type");
  }

  @Test
  void unknownAliasIsLeftAlone() {
    // `mystery.fn:` with no `mystery` in libraries: stays as-is; the worker fails handler lookup.
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
            libraries:
              git: "https://github.com/acme/git.git@v1.0"
            stages:
              - stage: build
                steps:
                  - mystery.fn: { x: 1 }
            """);
    StepModel step = model.getStages().get(0).getSteps().get(0);
    assertEquals("mystery.fn", step.getDescriptorId());
  }

  @Test
  void anAliasContainingADotIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parseAndValidate(
                    """
                    libraries:
                      foo.bar: "https://x@v1"
                    stages:
                      - stage: build
                        steps:
                          - sh: hi
                    """));
    assertTrue(e.getMessage().contains("'.'"), e.getMessage());
  }

  @Test
  void aliasEntryCanBeAnObjectWithCredential() {
    PipelineModel model =
        TitanYamlParser.parseAndValidate(
            """
            libraries:
              acme:
                url: "https://github.com/acme/private.git@v1.0"
                credential: GITHUB_TOKEN
            stages:
              - stage: build
                steps:
                  - acme.deploy: { service: billing }
            """);
    assertEquals("https://github.com/acme/private.git@v1.0", model.getLibraryAliases().get("acme"));
    assertEquals("GITHUB_TOKEN", model.getLibraryAliasCredentials().get("acme"));
    StepModel step = model.getStages().get(0).getSteps().get(0);
    assertEquals("libraryCall", step.getDescriptorId());
    assertEquals("GITHUB_TOKEN", step.getArguments().get("libraryCredential"));
  }

  @Test
  void aNonStringCoordinateIsRejected() {
    PipelineParseException e =
        assertThrows(
            PipelineParseException.class,
            () ->
                TitanYamlParser.parseAndValidate(
                    """
                    libraries:
                      git: 42
                    stages:
                      - stage: build
                        steps:
                          - sh: hi
                    """));
    assertTrue(e.getMessage().toLowerCase().contains("coordinate"), e.getMessage());
  }
}
