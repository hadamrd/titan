package io.adaptiq.titan.worker.step.builtin;

import io.adaptiq.titan.worker.step.LogSink;
import io.adaptiq.titan.worker.step.ParamSpec;
import io.adaptiq.titan.worker.step.StepDescriptor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The built-in {@code gitTag} step — annotates a commit with a git tag and optionally pushes it to
 * a remote (closes #758).
 *
 * <p>Release pipelines tag the commit being deployed. Before this step they shelled out to {@code
 * sh: 'git tag ... && git push --tags ...'}; that pattern works but loses Titan's first-class
 * argument validation, log masking, and credentials handling. This handler shells out to the {@code
 * git} CLI (same reasoning as {@link GitStepHandler}: the CLI is the reference behaviour, embedding
 * JGit would explode the dependency surface) and produces structured failures.
 *
 * <h2>Forms</h2>
 *
 * <pre>{@code
 * - gitTag: v1.2.3                       # scalar shorthand → tag = v1.2.3, lightweight, push=true
 * - gitTag:                              # object form, annotated tag, no push
 *     tag: v1.2.3
 *     message: "Release 1.2.3"
 *     ref: HEAD
 *     push: false
 * - gitTag:                              # authenticated push to origin
 *     tag: ${BUILD_VERSION}
 *     message: "Auto-tag for build ${BUILD_NUMBER}"
 *     credentialsRef: github-deploy
 * }</pre>
 *
 * <h2>Credentials (design/32 §6.3, ScriptStepHandler precedent)</h2>
 *
 * <p>The {@code credentialsRef} argument names a credential record resolved upstream by the
 * controller, exactly like {@code script}/{@code sh}: the orchestrator looks the credential up
 * against the credentials service and injects masked entries into {@link StepRequest#env()} before
 * dispatching the step. By convention an HTTPS git credential is delivered as {@code GIT_USERNAME}
 * + {@code GIT_PASSWORD} (PAT or password); an SSH credential as {@code GIT_SSH_COMMAND} pointing
 * to a pre-staged identity file.
 *
 * <p>This handler does the wiring on the worker side: when {@code GIT_USERNAME}/{@code
 * GIT_PASSWORD} are present, it writes a tiny {@code GIT_ASKPASS} helper script that echoes the
 * right secret based on git's "Username for ..." / "Password for ..." prompt, then sets {@code
 * GIT_ASKPASS} + {@code GIT_TERMINAL_PROMPT=0} on the {@code git push} environment. Plaintext
 * secrets <strong>never</strong> appear in {@code args} and never reach a process argv — they flow
 * exclusively through env vars and an on-disk helper that reads from env. The helper file is
 * deleted in a {@code finally}.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Pure git tag operations are <strong>not</strong> idempotent: a second {@code git tag v1.2.3}
 * on the same name fails with "tag already exists". This is intentional — re-tagging silently is a
 * release-engineering footgun (it would let CI overwrite an already-published release tag). A
 * reaped-and-retried task that finds the tag present surfaces a loud failure with stderr; the
 * pipeline author resolves it explicitly (delete the tag, bump the version, or guard the stage with
 * {@code when:}).
 */
public final class GitTagStepHandler implements StepHandler {

  /** Git refname rules (a relevant subset): tag must not contain whitespace or control chars. */
  private static final Pattern FORBIDDEN_IN_TAG = Pattern.compile("[\\s\\p{Cntrl}]");

  /**
   * Additional refname rules from git-check-ref-format(1) we enforce up-front so the user sees a
   * clear error rather than git's terse exit-128 stderr: no leading dash, no '..', no '~^:?*[\\',
   * no trailing '.lock', no '@{'.
   */
  private static final Pattern FORBIDDEN_TAG_CHARS = Pattern.compile("[~^:?*\\[\\\\]");

  @Override
  public String descriptorId() {
    return "gitTag";
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "gitTag",
        "Git tag",
        "Creates a git tag on a commit (lightweight by default; annotated when `message` is "
            + "given) and optionally pushes it to a remote. Not idempotent — re-tagging an existing "
            + "name fails with a clear error.",
        List.of(
            ParamSpec.required(
                "tag", "string", "Tag name (e.g. 'v1.2.3'). Supports ${VAR} env expansion."),
            ParamSpec.optional(
                "message",
                "string",
                "Tag message. If present, creates an annotated tag (-a -m); if absent, "
                    + "creates a lightweight tag."),
            ParamSpec.optional("ref", "string", "Commit to tag. Default: HEAD."),
            ParamSpec.optional("push", "boolean", "Push the tag after creating it (default true)."),
            ParamSpec.optional("remote", "string", "Remote name to push to (default 'origin')."),
            ParamSpec.optional(
                "credentialsRef",
                "string",
                "Credentials id; resolved by the controller and delivered to the worker as "
                    + "masked env vars (GIT_USERNAME/GIT_PASSWORD for HTTPS, GIT_SSH_COMMAND for "
                    + "SSH). NEVER plaintext in args.")),
        // design/42 §4.6: `gitTag: v1.2.3` parses to {value: "v1.2.3"}; the handler resolves
        // that generic `value` to `tag`.
        "tag");
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    // Accept either {tag: ...} (object form) or {value: ...} (scalar shorthand).
    String tag = request.argString("tag");
    if (tag == null || tag.isBlank()) {
      tag = request.argString("value");
    }
    if (tag == null || tag.isBlank()) {
      return StepResult.failed("gitTag step has no 'tag'");
    }
    tag = tag.trim();

    String validationError = validateTagName(tag);
    if (validationError != null) {
      return StepResult.failed("gitTag: invalid tag name '" + tag + "': " + validationError);
    }

    String message = request.argString("message");
    String ref = request.argString("ref", "HEAD").trim();
    boolean push = request.argBoolean("push", true);
    String remote = request.argString("remote", "origin").trim();
    String credentialsRef = request.argString("credentialsRef");
    LogSink log = request.log();

    // ── 0. Pre-flight: a checked-out git workspace must exist (closes #812) ──
    // `git tag` shells against the cwd's .git directory; TaskExecutor only creates
    // an empty workdir, so without a prior `checkout:`/`git:` step the bare git
    // failure is the cryptic "fatal: not a git repository". Catch this up-front
    // and emit a Titan-level remediation hint so fixture/pipeline authors aren't
    // left chasing git's error.
    if (!Files.isDirectory(request.workDir().resolve(".git"))) {
      return StepResult.failed(
          "gitTag: no git workspace at "
              + request.workDir()
              + " (no .git directory). The gitTag step requires a checked-out git "
              + "workspace; add a `checkout:` (or `git:`) step before this one.");
    }

    // ── 1. Create the tag locally ─────────────────────────────────────
    List<String> tagCmd = new ArrayList<>();
    tagCmd.add("git");
    tagCmd.add("tag");
    if (message != null && !message.isBlank()) {
      tagCmd.add("-a");
      tagCmd.add("-m");
      tagCmd.add(message);
      log.system("gitTag: creating annotated tag '" + tag + "' at " + ref);
    } else {
      log.system("gitTag: creating lightweight tag '" + tag + "' at " + ref);
    }
    tagCmd.add(tag);
    tagCmd.add(ref);

    int tagExit = request.executor().run(tagCmd, request.workDir(), request.env(), log);
    if (tagExit != 0) {
      // The executor has already streamed stderr to the log; surface a clear message that
      // includes the most common cause (tag already exists) so a reaped retry's failure is
      // explicable from the result alone, not just the log.
      return StepResult.failed(
          tagExit,
          "gitTag: 'git tag' failed (exit "
              + tagExit
              + "). Most common cause: tag '"
              + tag
              + "' already exists locally. See step log for stderr.");
    }

    // Resolve the commit SHA the tag points to. We use `git rev-list -n 1 <tag>` (rather than
    // `rev-parse <tag>^{commit}`) because it returns the dereferenced commit SHA uniformly for
    // both lightweight and annotated tags — annotated tag objects would otherwise resolve to
    // the tag object's own SHA, not the commit. Captured via a direct ProcessBuilder because
    // the StepExecutor SPI only streams stdout to the log; this is a tiny read-only follow-up
    // against a workspace we already validated and just wrote to.
    String refCommitSha = resolveTagCommitSha(request.workDir(), tag, log);

    if (!push) {
      log.system("gitTag: push=false — tag created locally only");
      publishOutputs(request, tag, false, refCommitSha);
      return StepResult.success(0);
    }

    // ── 2. Push the tag ───────────────────────────────────────────────
    Map<String, String> pushEnv = new LinkedHashMap<>(request.env());
    Path askpass = null;
    try {
      try {
        askpass = maybeWireCredentials(request, credentialsRef, pushEnv, log);
      } catch (CredentialsWiringException e) {
        // A misconfigured credentialsRef must surface as a structured Titan failure, not bubble
        // up as a raw IOException — the orchestrator treats the latter as worker infrastructure
        // breakage, but this is a pipeline-author error.
        return StepResult.failed("gitTag: " + e.getMessage());
      }

      List<String> pushCmd = new ArrayList<>();
      pushCmd.add("git");
      pushCmd.add("push");
      pushCmd.add(remote);
      pushCmd.add(tag);

      log.system("gitTag: pushing tag '" + tag + "' to remote '" + remote + "'");
      int pushExit = request.executor().run(pushCmd, request.workDir(), pushEnv, log);
      if (pushExit != 0) {
        String detail = pushFailureDetail(credentialsRef, remote);
        return StepResult.failed(
            pushExit, "gitTag: 'git push' failed (exit " + pushExit + "). " + detail);
      }
      log.system("gitTag: tag '" + tag + "' pushed to '" + remote + "'");
      publishOutputs(request, tag, true, refCommitSha);
      return StepResult.success(0);
    } finally {
      if (askpass != null) {
        try {
          Files.deleteIfExists(askpass);
        } catch (IOException ignored) {
          // best-effort cleanup; the temp dir is workspace-local and reaped per build
        }
      }
    }
  }

  /**
   * Wire credentials onto {@code pushEnv} if {@code credentialsRef} was supplied. The orchestrator
   * is expected to have already injected the secret values into {@link StepRequest#env()}
   * (design/32 §6.3 — credential resolution is controller-side; the worker only sees env vars).
   * This method writes a {@code GIT_ASKPASS} helper script and points {@code pushEnv.GIT_ASKPASS}
   * at it. Returns the helper path so the caller can delete it in a {@code finally}.
   *
   * <p>Returns {@code null} when no askpass helper was created (no credentialsRef, or only an SSH
   * credential which needs no askpass).
   */
  private static Path maybeWireCredentials(
      StepRequest request, String credentialsRef, Map<String, String> pushEnv, LogSink log)
      throws IOException, CredentialsWiringException {
    if (credentialsRef == null || credentialsRef.isBlank()) {
      return null;
    }
    log.system("gitTag: applying credentialsRef '" + credentialsRef + "'");

    String username = pushEnv.get("GIT_USERNAME");
    String password = pushEnv.get("GIT_PASSWORD");
    String sshCommand = pushEnv.get("GIT_SSH_COMMAND");

    if (sshCommand != null && !sshCommand.isBlank()) {
      // SSH path: the orchestrator pre-staged the key + GIT_SSH_COMMAND. Nothing else to do —
      // git push will pick it up from the env.
      log.system("gitTag: using GIT_SSH_COMMAND from credentialsRef");
      return null;
    }

    if (username != null && password != null) {
      Path askpass = writeAskpassHelper(request.workDir());
      pushEnv.put("GIT_ASKPASS", askpass.toAbsolutePath().toString());
      // Without this, git falls back to /dev/tty when ASKPASS rejects/empty — hangs the worker.
      pushEnv.put("GIT_TERMINAL_PROMPT", "0");
      log.system("gitTag: using GIT_ASKPASS helper for HTTPS auth");
      return askpass;
    }

    // credentialsRef was set but no credential env vars were injected — fail loud rather than
    // silently attempt an unauthenticated push (which would either succeed against a public
    // remote and hide the misconfiguration, or hang waiting for terminal input).
    throw new CredentialsWiringException(
        "credentialsRef '"
            + credentialsRef
            + "' was supplied but neither GIT_USERNAME/GIT_PASSWORD nor GIT_SSH_COMMAND were "
            + "delivered to the worker — check that the credential is resolvable and that the "
            + "controller's credentials service is wired");
  }

  /**
   * Publish the gitTag step's outputs (closes #813): the resolved tag name, whether it was pushed,
   * and the commit SHA the tag points to. Called only on the success path — a failed step has no
   * semantic outputs (downstream {@code ${{ steps['Tag'].outputs.tag }}} references on a failed
   * step must remain unresolved). The {@code ref} output is omitted when SHA resolution failed
   * (best-effort: the tag is already created locally, surfacing an empty ref is worse than no ref).
   */
  private static void publishOutputs(
      StepRequest request, String tag, boolean pushed, String refCommitSha) {
    request.outputs().put("tag", tag);
    request.outputs().put("pushed", String.valueOf(pushed));
    if (refCommitSha != null && !refCommitSha.isBlank()) {
      request.outputs().put("ref", refCommitSha);
    }
  }

  /**
   * Resolve the commit SHA the freshly-created tag points to. Uses {@code git rev-list -n 1 <tag>}
   * so the result is the dereferenced commit SHA for both lightweight and annotated tags. Returns
   * {@code null} on any failure — the caller treats SHA resolution as best-effort observability,
   * not a step-failure condition.
   */
  private static String resolveTagCommitSha(Path workDir, String tag, LogSink log) {
    try {
      Process p =
          new ProcessBuilder("git", "rev-list", "-n", "1", tag)
              .directory(workDir.toFile())
              .redirectErrorStream(false)
              .start();
      String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      int exit = p.waitFor();
      if (exit != 0 || out.isBlank()) {
        log.system("gitTag: could not resolve SHA for tag '" + tag + "' (exit " + exit + ")");
        return null;
      }
      return out;
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.system("gitTag: SHA resolution for tag '" + tag + "' failed: " + e.getMessage());
      return null;
    }
  }

  /** A pipeline-author error in credentials wiring — distinct from infrastructure I/O failure. */
  private static final class CredentialsWiringException extends Exception {
    private static final long serialVersionUID = 1L;

    CredentialsWiringException(String message) {
      super(message);
    }
  }

  /**
   * Write a tiny POSIX sh {@code GIT_ASKPASS} helper that echoes {@code $GIT_USERNAME} or {@code
   * $GIT_PASSWORD} depending on the prompt git passes as {@code $1}. The secret values are read
   * from the process env at askpass-invocation time — never embedded in the script body, never
   * written to disk in plaintext.
   */
  private static Path writeAskpassHelper(Path workDir) throws IOException {
    Path helper = Files.createTempFile(workDir, "titan-gitaskpass-", ".sh");
    String body =
        "#!/bin/sh\n"
            + "# Titan gitTag step — GIT_ASKPASS helper.\n"
            + "# Secrets are read from env at runtime; no plaintext is stored here.\n"
            + "case \"$1\" in\n"
            + "  Username*) printf '%s' \"$GIT_USERNAME\" ;;\n"
            + "  Password*) printf '%s' \"$GIT_PASSWORD\" ;;\n"
            + "  *)         printf '%s' \"$GIT_PASSWORD\" ;;\n"
            + "esac\n";
    Files.writeString(helper, body, StandardCharsets.UTF_8);
    try {
      Set<PosixFilePermission> perms = new HashSet<>();
      perms.add(PosixFilePermission.OWNER_READ);
      perms.add(PosixFilePermission.OWNER_WRITE);
      perms.add(PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(helper, PosixFilePermissions.asFileAttribute(perms).value());
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX filesystem (Windows dev box). The executable bit is irrelevant there —
      // git on Windows invokes askpass through the shell wrapper that ships with Git for Windows.
    }
    return helper;
  }

  /** Build a remediation hint for a push failure based on the credentials wiring chosen. */
  private static String pushFailureDetail(String credentialsRef, String remote) {
    if (credentialsRef == null || credentialsRef.isBlank()) {
      return "No credentialsRef was supplied — if remote '"
          + remote
          + "' requires authentication, set credentialsRef. Common other cause: the tag already "
          + "exists on the remote (non-fast-forward). See step log for stderr.";
    }
    return "Credentials were applied from credentialsRef. Common causes: the credential lacks "
        + "push rights on remote '"
        + remote
        + "', or the tag already exists on the remote (non-fast-forward). See step log for stderr.";
  }

  /**
   * Validate the tag name BEFORE shelling out to git, so a malformed input produces a structured
   * Titan error rather than git's exit-128 "fatal: '...' is not a valid ref name". Enforces the
   * subset of git-check-ref-format(1) rules that catch the obvious mistakes; git itself is the
   * authoritative validator for the rest (and its failure is surfaced as a regular tag-exit
   * failure).
   *
   * @return {@code null} when valid, or an explanation when not
   */
  static String validateTagName(String tag) {
    if (tag.isEmpty()) {
      return "empty";
    }
    if (FORBIDDEN_IN_TAG.matcher(tag).find()) {
      return "contains whitespace or control characters";
    }
    if (FORBIDDEN_TAG_CHARS.matcher(tag).find()) {
      return "contains one of the forbidden characters ~ ^ : ? * [ \\";
    }
    if (tag.startsWith("-")) {
      return "starts with '-' (would be parsed as a git option)";
    }
    if (tag.contains("..")) {
      return "contains '..'";
    }
    if (tag.contains("@{")) {
      return "contains '@{'";
    }
    if (tag.endsWith(".lock")) {
      return "ends with '.lock'";
    }
    if (tag.endsWith("/") || tag.endsWith(".")) {
      return "ends with '/' or '.'";
    }
    return null;
  }
}
