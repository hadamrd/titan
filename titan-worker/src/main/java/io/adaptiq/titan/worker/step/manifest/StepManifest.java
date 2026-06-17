package io.adaptiq.titan.worker.step.manifest;

import java.util.List;
import java.util.Objects;

/**
 * The parsed form of a Tier-1 step manifest — a {@code *.titanstep.yaml} file (design/42 §4.8, §8).
 *
 * <p>A Tier-1 step is <strong>pure data</strong>: no jar, no Java, no {@code ServiceLoader}. The
 * manifest declares a container step — the GitHub-Actions <em>container-action</em> analog: a
 * container {@link #image()} plus an argv {@link #command()} with {@code ${{ args.<name> }}}
 * placeholders. It is the jar-free default path for a step extension (design/42 §2, §6).
 *
 * <p>This record is the in-memory model of one manifest file; {@link StepManifestLoader} parses a
 * file into it, and {@code ContainerManifestStepHandler} runs it. A composite (sequence) manifest
 * shape is deferred to a later chunk and is intentionally not modelled here.
 *
 * @param step the {@code descriptorId} this manifest contributes — required
 * @param image the container image the step runs in — required for a container step
 * @param command the argv to run inside {@link #image()}, with {@code ${{ args.<name> }}}
 *     placeholders — required, non-empty
 * @param params the step's declared arguments — drives §4.5 argument validation
 * @param displayName a human label for the palette, or {@code null}
 * @param help a one-paragraph description, or {@code null}
 * @param origin the file the manifest was loaded from — for the startup audit line
 */
public record StepManifest(
    String step,
    String image,
    List<String> command,
    List<ManifestParam> params,
    String displayName,
    String help,
    String origin) {

  public StepManifest {
    command = command == null ? List.of() : List.copyOf(command);
    params = params == null ? List.of() : List.copyOf(params);
  }

  /**
   * One declared parameter of a manifest step — the {@code params:} list entry (design/42 §8).
   *
   * @param name the argument key, as written in the pipeline YAML
   * @param type a schema type — {@code string}, {@code boolean}, {@code number}, {@code list}
   * @param required whether the step rejects a request omitting this argument
   */
  public record ManifestParam(String name, String type, boolean required) {
    public ManifestParam {
      Objects.requireNonNull(name, "param name");
      type = type == null || type.isBlank() ? "string" : type;
    }
  }
}
