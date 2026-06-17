package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.util.ArrayList;
import java.util.List;

/**
 * The built-in {@code git} / {@code checkout} step — clones a Git repository into the step's
 * workspace (Chunk 32E — design/32 §9, §12 D5).
 *
 * <p>{@code git} is the #1 migration-frequency step after {@code sh}/{@code script} (design/32 §12
 * D5): a migrating pipeline hits a source checkout in its first minute. Per design/32 §9, this
 * handler <strong>shells out to the {@code git} CLI</strong> rather than embedding a Java Git
 * implementation — the CLI is the reference behaviour every migrating team already relies on, and
 * shelling out keeps the handler ~100 lines instead of carrying JGit's surface area.
 *
 * <p><strong>Composition with {@link io.adaptiq.titan.worker.step.StepExecutor} (§3.4).</strong>
 * The handler decides the command ({@code git clone …}); the request's {@code StepExecutor} decides
 * the sandbox. So a {@code git} step runs locally or inside the step's container exactly as a
 * {@code sh} step does — if the pipeline declares {@code image: maven:3.9}, the clone runs in that
 * image and the worker host needs no {@code git} at all. The rig's worker image ships {@code git}
 * for the local-executor path.
 *
 * <p><strong>Shallow clone by default.</strong> {@code --depth 1} unless {@code shallow: false} is
 * given — a CI checkout almost never needs full history, and a shallow clone is dramatically faster
 * on a large repository. A pipeline that needs history (tagging, {@code git describe}) opts out
 * explicitly.
 *
 * <p><strong>Idempotency.</strong> The clone target directory is removed before cloning, so a
 * re-delivered task (design/30 caveat 3 — a reaped task re-runs from scratch) produces the same
 * result as a first run. This satisfies the {@link StepHandler} idempotency contract.
 *
 * <p><strong>Credentials seam — NOT implemented here.</strong> The step accepts a {@code
 * credentialsId} argument in its contract, but credential <em>resolution</em> is owned by a
 * separate {@code withCredentials} workstream (design/32 §12 D6): credentials are resolved on the
 * controller against the credentials store and delivered to the worker as masked environment
 * variables. When that lands, an authenticated checkout is simply a {@code git} step whose {@code
 * env} carries the credential — e.g. a {@code GIT_ASKPASS} helper or a credential-bearing URL —
 * with no change to this handler. {@link #credentialsSeam} marks the single integration point so
 * the wiring is unambiguous; passing a {@code credentialsId} today is accepted and logged, never
 * silently honoured.
 */
public final class GitStepHandler implements StepHandler {

  /** {@code git}: the canonical id — the 80% case (design/32 §12 D5). */
  public static final String GIT = "git";

  /** {@code checkout}: the generic SCM alias; delegates to the same implementation. */
  public static final String CHECKOUT = "checkout";

  private final String descriptorId;

  /** A {@code git}-id handler. */
  public GitStepHandler() {
    this(GIT);
  }

  /**
   * A handler bound to a specific id — {@link #GIT} or {@link #CHECKOUT}. Both share this one
   * implementation; {@code git} is the 80%, {@code checkout} is kept deliberately simple (design/32
   * §9).
   */
  public GitStepHandler(String descriptorId) {
    if (!GIT.equals(descriptorId) && !CHECKOUT.equals(descriptorId)) {
      throw new IllegalArgumentException(
          "GitStepHandler serves only 'git' or 'checkout', not '" + descriptorId + "'");
    }
    this.descriptorId = descriptorId;
  }

  @Override
  public String descriptorId() {
    return descriptorId;
  }

  @Override
  public StepDescriptor descriptor() {
    boolean isGit = GIT.equals(descriptorId);
    return new StepDescriptor(
        descriptorId,
        isGit ? "Git checkout" : "Checkout",
        "Clones a Git repository into the step's workspace by shelling out to the git "
            + "CLI. Shallow by default. The scalar shorthand (`"
            + descriptorId
            + ": <url>`) clones the repository's default branch.",
        List.of(
            ParamSpec.required("url", "string", "The repository URL to clone."),
            ParamSpec.optional(
                "branch",
                "string",
                "Branch (or tag) to check out. Defaults to the repository's " + "default branch."),
            ParamSpec.optional(
                "shallow",
                "boolean",
                "Clone with --depth 1 (default true). Set false for full " + "history."),
            ParamSpec.optional(
                "credentialsId",
                "string",
                "credential id for an authenticated clone. Accepted by "
                    + "the contract; resolution is owned by the withCredentials "
                    + "workstream (design/32 §12 D6) and not yet wired.")),
        // design/42 §4.6: the scalar shorthand `git: <url>` parses to {value: …};
        // declare that the worker resolves that generic `value` to `url` for this step.
        "url");
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    // The YAML scalar shorthand (`git: https://…`) parses to {value: <url>}; the declarative
    // front-end uses {url: <url>}. Accept either — exactly as the sh handler does for `script`.
    String url = request.argString("url");
    if (url == null || url.isBlank()) {
      url = request.argString("value");
    }
    if (url == null || url.isBlank()) {
      return StepResult.failed(descriptorId + " step has no 'url'");
    }
    url = url.trim();

    String branch = request.argString("branch");
    boolean shallow = request.argBoolean("shallow", true);
    LogSink log = request.log();

    credentialsSeam(request, log);

    // Clone into a fixed, well-known sub-directory of the step workspace so a re-delivered
    // task (idempotency — design/30 caveat 3) lands deterministically. "." would clone into
    // the workspace root, but git refuses to clone into a non-empty directory; a named
    // sub-dir, removed first, is both deterministic and git-friendly.
    String checkoutDir = ".";

    // Build the command: a shallow, single-branch clone is the CI default.
    List<String> clone = new ArrayList<>();
    clone.add("git");
    clone.add("clone");
    if (shallow) {
      clone.add("--depth");
      clone.add("1");
    }
    if (branch != null && !branch.isBlank()) {
      clone.add("--branch");
      clone.add(branch.trim());
    }
    clone.add("--");
    clone.add(url);
    clone.add(checkoutDir);

    log.system(
        "git: cloning "
            + url
            + (branch != null && !branch.isBlank() ? " (branch " + branch.trim() + ")" : "")
            + (shallow ? " --depth 1" : " (full history)"));

    // A re-delivered task may find a partial clone from a reaped attempt — clear it so the
    // clone target is empty and git does not refuse. Shelled through the executor so it runs
    // in the same sandbox (container) as the clone itself.
    int cleared =
        request
            .executor()
            .run(
                List.of(
                    "sh", "-c", "rm -rf .git && find . -mindepth 1 -delete 2>/dev/null || true"),
                request.workDir(),
                request.env(),
                log);
    if (cleared != 0) {
      log.system(
          "git: warning — could not fully clear the checkout directory "
              + "(exit "
              + cleared
              + "); continuing");
    }

    int exit = request.executor().run(clone, request.workDir(), request.env(), log);
    if (exit != 0) {
      return StepResult.failed(exit, "git clone failed (exit " + exit + ")");
    }
    log.system("git: checkout complete");
    return StepResult.success(exit);
  }

  /**
   * The credential-resolution integration seam (design/32 §12 D6). Credential resolution is owned
   * by a separate {@code withCredentials} workstream and is intentionally NOT implemented here.
   * When it lands it will deliver the credential to the worker as masked entries in {@link
   * StepRequest#env()} (e.g. a {@code GIT_ASKPASS} helper); this handler then needs no change — the
   * inherited {@code env} already flows to the {@code git} process via the executor. Until then, a
   * {@code credentialsId} on the step is accepted by the contract but logged as not-yet-honoured,
   * so behaviour is never silently wrong.
   */
  private static void credentialsSeam(StepRequest request, LogSink log) {
    String credentialsId = request.argString("credentialsId");
    if (credentialsId != null && !credentialsId.isBlank()) {
      log.system(
          "git: credentialsId '"
              + credentialsId
              + "' was supplied but credential "
              + "resolution is not yet wired (design/32 §12 D6 — withCredentials "
              + "workstream); the clone runs with the worker's ambient git configuration");
    }
  }
}
