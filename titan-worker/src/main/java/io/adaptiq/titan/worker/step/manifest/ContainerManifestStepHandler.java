package io.adaptiq.titan.worker.step.manifest;

import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.ContainerExecutor;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Tier-1 <strong>container step</strong> — a {@link StepHandler} backed by a data-only {@link
 * StepManifest} rather than a jar (design/42 §4.8, §6, §8).
 *
 * <p>This is the GitHub-Actions <em>container-action</em> analog and the jar-free default path for
 * a step extension. It contributes a step type to the registry through the exact same {@code
 * StepHandler} contract as a socle or Tier-2 handler — so 42-V's argument validation, the audit
 * log, and the duplicate-id rule all apply to it for free.
 *
 * <p><strong>The descriptor.</strong> {@link #descriptor()} is built from the manifest: {@code
 * descriptorId} is {@code manifest.step()}, the {@link ParamSpec}s are derived from {@code
 * manifest.params()}. The worker's pre-{@code execute} validator (design/42 §4.5) therefore checks
 * the request against the manifest's declared grammar with no extra wiring.
 *
 * <p><strong>execute.</strong> The handler:
 *
 * <ol>
 *   <li>substitutes every {@code ${{ args.<name> }}} placeholder in the manifest {@code command}
 *       from {@link StepRequest#arguments()} — see {@link #substitute};
 *   <li>runs the resulting argv <em>in the manifest's {@code image}</em> via a {@link
 *       ContainerExecutor}. The manifest's image is the step's own execution environment: it is
 *       independent of the pipeline {@code image:} scope (which selects the executor injected into
 *       the {@link StepRequest}). A container step always runs in <em>its</em> image — that is the
 *       point of the §4.8 model — so the handler builds its own executor for that image rather than
 *       using {@code request.executor()}. Container launching itself is not reinvented: {@link
 *       ContainerExecutor} (design/31) is reused unchanged.
 *   <li>returns {@link StepResult#success}/{@link StepResult#failed} from the exit code, exactly as
 *       {@code GitStepHandler} does.
 * </ol>
 *
 * <p><strong>Idempotency.</strong> Running an image with argv is idempotent to the extent the image
 * is — the handler itself holds no state across runs, so a re-delivered task (design/30 caveat 3)
 * re-runs cleanly.
 */
public final class ContainerManifestStepHandler implements StepHandler {

  /**
   * The placeholder grammar: {@code ${{ args.<name> }}} with flexible inner whitespace. The
   * captured group is the argument name. This mirrors design/42 §8's literal example.
   */
  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\$\\{\\{\\s*args\\.([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

  private final StepManifest manifest;

  public ContainerManifestStepHandler(StepManifest manifest) {
    this.manifest = manifest;
  }

  /** The manifest this handler runs — exposed for the audit log and tests. */
  public StepManifest manifest() {
    return manifest;
  }

  @Override
  public String descriptorId() {
    return manifest.step();
  }

  @Override
  public StepDescriptor descriptor() {
    List<ParamSpec> params = new ArrayList<>(manifest.params().size());
    for (StepManifest.ManifestParam p : manifest.params()) {
      params.add(
          new ParamSpec(
              p.name(),
              p.type(),
              p.required(),
              "Argument of the container step '" + manifest.step() + "'.",
              List.of()));
    }
    String help =
        manifest.help() != null
            ? manifest.help()
            : "Tier-1 container step — runs the image '"
                + manifest.image()
                + "' (manifest: "
                + manifest.origin()
                + ").";
    String displayName = manifest.displayName() != null ? manifest.displayName() : manifest.step();
    return new StepDescriptor(manifest.step(), displayName, help, params, null);
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    LogSink log = request.log();

    // (1) Substitute ${{ args.<name> }} placeholders in the manifest command. A placeholder
    // that references an argument absent from the request is a clean failure — though 42-V
    // should already have rejected a missing *required* param before we get here.
    List<String> argv;
    try {
      argv = substitute(manifest.command(), request);
    } catch (MissingArgument e) {
      return StepResult.failed(e.getMessage());
    }

    // (2) Run the argv inside the manifest's own image — its execution environment, independent
    // of the pipeline image: scope. ContainerExecutor (design/31) is reused as-is.
    StepExecutor executor =
        new ContainerExecutor(
            manifest.image(),
            "manifest-step:" + manifest.step(),
            manifest.step() + "@build-" + request.buildId(),
            String.valueOf(request.buildId()),
            request.nodeId(),
            () -> false);

    log.system("container step '" + manifest.step() + "': running image " + manifest.image());
    int exit = executor.run(argv, request.workDir(), request.env(), log);
    if (exit != 0) {
      return StepResult.failed(
          exit, "container step '" + manifest.step() + "' failed (exit " + exit + ")");
    }
    return StepResult.success(exit);
  }

  /**
   * Substitute {@code ${{ args.<name> }}} placeholders in {@code command} from {@code
   * request.arguments()}.
   *
   * <p><strong>The rule.</strong> Each {@code command} entry is scanned for the {@code ${{
   * args.<name> }}} pattern (flexible inner whitespace). Every match is replaced by the string
   * value of {@code request.argString(name)}. A placeholder may appear anywhere within an entry and
   * an entry may contain several; substitution is purely textual. A placeholder referencing an
   * argument key that is <em>absent</em> from the request fails the step cleanly (a {@link
   * MissingArgument}); a {@code null}-valued argument substitutes the empty string. A {@code
   * command} entry with no placeholder is passed through verbatim.
   *
   * @throws MissingArgument if a referenced argument key is not present in the request
   */
  static List<String> substitute(List<String> command, StepRequest request) {
    List<String> out = new ArrayList<>(command.size());
    for (String entry : command) {
      Matcher m = PLACEHOLDER.matcher(entry);
      StringBuilder sb = new StringBuilder();
      while (m.find()) {
        String name = m.group(1);
        if (!request.arguments().containsKey(name)) {
          throw new MissingArgument(
              "container step command references ${{ args."
                  + name
                  + " }} but no '"
                  + name
                  + "' argument was supplied");
        }
        String value = request.argString(name, "");
        m.appendReplacement(sb, Matcher.quoteReplacement(value));
      }
      m.appendTail(sb);
      out.add(sb.toString());
    }
    return out;
  }

  /** A command placeholder referenced an argument the request did not carry. */
  static final class MissingArgument extends RuntimeException {
    private static final long serialVersionUID = 1L;

    MissingArgument(String message) {
      super(message);
    }
  }
}
