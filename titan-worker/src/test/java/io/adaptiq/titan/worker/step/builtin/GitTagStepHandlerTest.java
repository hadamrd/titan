package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.adaptiq.titan.worker.step.StepExecutor;
import io.adaptiq.titan.worker.step.StepHandler;
import io.adaptiq.titan.worker.step.StepHandlerTck;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link GitTagStepHandler} — the shared TCK contract plus {@code gitTag}-specific behaviour
 * (closes #758).
 *
 * <p>Each test builds a fresh local {@code file://} bare remote + a local clone in the workspace —
 * no network. On a host without {@code git} on {@code PATH} the class self-skips, matching the
 * {@link GitStepHandlerTest} pattern. The workspace is per-test ({@code @TempDir workDir} on the
 * TCK base) so a created tag in one test cannot leak into another.
 */
class GitTagStepHandlerTest extends StepHandlerTck {

  /** Bare upstream that the workspace clone pushes back to — created per test. */
  private Path bareRemote;

  @BeforeEach
  void prepareLocalRepo() throws Exception {
    assumeTrue(gitAvailable(), "git CLI not on PATH — skipping gitTag tests");

    // 1. The bare remote, target of `git push`.
    bareRemote = Files.createTempDirectory("titan-gittag-remote-");
    git(bareRemote, "init", "--bare", "--initial-branch=main");

    // 2. The working clone in the TCK's workDir, with one commit on `main`, wired to the bare.
    git(workDir, "init", "--initial-branch=main");
    git(workDir, "config", "user.email", "tck@titan.test");
    git(workDir, "config", "user.name", "Titan TCK");
    git(workDir, "remote", "add", "origin", bareRemote.toUri().toString());
    Files.writeString(workDir.resolve("README.md"), "titan-gittag\n", StandardCharsets.UTF_8);
    git(workDir, "add", "README.md");
    git(workDir, "commit", "-m", "initial commit");
    // Push so HEAD on remote exists; later we'll just push tags.
    git(workDir, "push", "-u", "origin", "main");
  }

  private static boolean gitAvailable() {
    try {
      return new ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor()
          == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static void git(Path dir, String... args) throws IOException, InterruptedException {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    Process p =
        new ProcessBuilder(cmd)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start();
    int exit = p.waitFor();
    if (exit != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " exited " + exit);
    }
  }

  /** Run a git command and return its stdout (trimmed). */
  private static String gitOut(Path dir, String... args) throws IOException, InterruptedException {
    String[] cmd = new String[args.length + 1];
    cmd[0] = "git";
    System.arraycopy(args, 0, cmd, 1, args.length);
    Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(false).start();
    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    p.waitFor();
    return out;
  }

  // ── TCK wiring ────────────────────────────────────────────────────────

  @Override
  protected StepHandler newHandler() {
    return new GitTagStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    // The TCK's executeOnValidArgumentsSucceeds drives this — lightweight tag, no push, so it's
    // independent of any remote being up.
    return Map.of("tag", "v0.0.1-tck", "push", false);
  }

  private StepRequest requestWith(Map<String, Object> arguments, CapturingLog log) {
    return new StepRequest(
        "gitTag",
        arguments,
        workDir,
        Map.of(),
        1L,
        "node-1",
        null,
        new LocalProcessExecutor(),
        log,
        new CapturingOutputs());
  }

  private StepRequest requestWith(
      Map<String, Object> arguments, Map<String, String> env, CapturingLog log) {
    return new StepRequest(
        "gitTag",
        arguments,
        workDir,
        env,
        1L,
        "node-1",
        null,
        new LocalProcessExecutor(),
        log,
        new CapturingOutputs());
  }

  // ── gitTag-specific behaviour ─────────────────────────────────────────

  @Test
  void createsLightweightTagAndPushesItByDefault() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult result = new GitTagStepHandler().execute(requestWith(Map.of("tag", "v1.0.0"), log));

    assertTrue(result.isSuccess(), log.text());
    // Lightweight tag: git cat-file -t v1.0.0 → "commit" (lightweight) not "tag" (annotated)
    assertEquals("commit", gitOut(workDir, "cat-file", "-t", "v1.0.0"));
    // Pushed to the bare remote
    String remoteTags = gitOut(bareRemote, "tag", "--list");
    assertTrue(remoteTags.contains("v1.0.0"), "tag should be on bare remote: " + remoteTags);
  }

  @Test
  void annotatedTagWhenMessageGiven() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitTagStepHandler()
            .execute(
                requestWith(
                    Map.of("tag", "v1.1.0", "message", "Release 1.1.0", "push", false), log));

    assertTrue(result.isSuccess(), log.text());
    // Annotated: cat-file -t returns "tag"; lightweight would return "commit".
    assertEquals(
        "tag",
        gitOut(workDir, "cat-file", "-t", "v1.1.0"),
        "annotated tag must be a 'tag' object, not 'commit'");
  }

  @Test
  void reTaggingAnExistingTagFailsWithClearMessage() throws Exception {
    // Tag once, then attempt again. The second call must fail loudly — pure git is not
    // idempotent on tag creation and silently overwriting a released tag is dangerous.
    CapturingLog log = new CapturingLog();
    StepResult first =
        new GitTagStepHandler().execute(requestWith(Map.of("tag", "v2.0.0", "push", false), log));
    assertTrue(first.isSuccess(), log.text());

    StepResult second =
        new GitTagStepHandler().execute(requestWith(Map.of("tag", "v2.0.0", "push", false), log));
    assertFalse(second.isSuccess(), "re-tagging the same name must fail");
    assertNotNull(second.message());
    assertTrue(
        second.message().contains("already exists"),
        "failure must explain the most likely cause: " + second.message());
  }

  @Test
  void malformedTagNameIsRejectedBeforeAnyGitCall() throws Exception {
    // A space in the tag name is invalid per git-check-ref-format. We validate up-front so the
    // user sees a Titan-structured error, not git's terse exit-128 — and so a misconfigured
    // pipeline cannot accidentally invoke git with a bogus argv.
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitTagStepHandler().execute(requestWith(Map.of("tag", "v1 with space"), log));

    assertFalse(result.isSuccess());
    assertNotNull(result.message());
    assertTrue(
        result.message().contains("invalid tag name"),
        "must surface a validation error, not a git exit code: " + result.message());
    // Confirm git was never invoked: no tag exists locally.
    assertEquals("", gitOut(workDir, "tag", "--list"));
  }

  @Test
  void pushFalseCreatesTagLocallyOnly() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitTagStepHandler()
            .execute(requestWith(Map.of("tag", "v3.0.0-local", "push", false), log));

    assertTrue(result.isSuccess(), log.text());
    // Local tag exists
    assertTrue(gitOut(workDir, "tag", "--list").contains("v3.0.0-local"));
    // Remote does NOT have the tag
    String remoteTags = gitOut(bareRemote, "tag", "--list");
    assertFalse(
        remoteTags.contains("v3.0.0-local"),
        "push=false must not push to remote, but remote has: " + remoteTags);
  }

  @Test
  void credentialsRefWithoutInjectedSecretsFailsLoudly() throws Exception {
    // Simulate a misconfigured controller: credentialsRef is supplied on the step, but the
    // orchestrator failed to inject GIT_USERNAME/GIT_PASSWORD (or GIT_SSH_COMMAND) into env.
    // The handler must reject rather than silently attempt an unauthenticated push (which on a
    // remote that requires auth would hang waiting on a terminal prompt).
    //
    // We point at an HTTPS URL that will require auth so the failure path is unambiguous even
    // if the env-detection logic regresses. The handler's loud failure happens BEFORE git is
    // invoked, so the URL is never actually contacted.
    git(workDir, "remote", "set-url", "origin", "https://localhost:1/titan-gittag-test.git");
    CapturingLog log = new CapturingLog();

    StepResult result =
        new GitTagStepHandler()
            .execute(
                requestWith(
                    Map.of("tag", "v4.0.0-auth", "credentialsRef", "deploy-key"),
                    new HashMap<>(),
                    log));

    assertFalse(result.isSuccess(), "must fail when credentialsRef is set but no creds delivered");
    assertNotNull(result.message());
    String msg = result.message().toLowerCase(java.util.Locale.ROOT);
    assertTrue(
        msg.contains("credentialsref")
            || msg.contains("git_username")
            || msg.contains("git_ssh_command"),
        "failure must name the missing credential wiring: " + result.message());
  }

  @Test
  void missingGitWorkspaceFailsWithRemediationHint() throws Exception {
    // #812: TaskExecutor only creates an empty workdir; without a prior `checkout:` step,
    // `git tag` would fail with the cryptic "fatal: not a git repository". The handler must
    // pre-flight and emit a Titan-level remediation hint instead.
    Path emptyDir = Files.createTempDirectory("titan-gittag-nocheckout-");
    CapturingLog log = new CapturingLog();
    StepRequest request =
        new StepRequest(
            "gitTag",
            Map.of("tag", "v0.0.1", "push", false),
            emptyDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            log,
            new CapturingOutputs());

    StepResult result = new GitTagStepHandler().execute(request);

    assertFalse(result.isSuccess(), "must fail when workdir has no .git");
    assertNotNull(result.message());
    String msg = result.message();
    assertTrue(msg.contains("checkout"), "failure must hint at the missing checkout step: " + msg);
    assertTrue(
        msg.contains(".git") || msg.contains("git workspace"),
        "failure must name the missing .git workspace: " + msg);
    // Confirm git was never invoked: directory is still empty.
    assertEquals(0, Files.list(emptyDir).count(), "no git invocation should have occurred");
  }

  // ── #813: outputs publication ────────────────────────────────────────

  @Test
  void publishesTagPushedAndRefOutputsOnHappyPath() throws Exception {
    // Spec #30 (#808/#811) drove this: downstream steps must reference
    // ${{ steps['Tag'].outputs.tag }} instead of log-scraping. The handler
    // publishes the resolved tag, the pushed flag, and the commit SHA the
    // tag points to.
    CapturingLog log = new CapturingLog();
    CapturingOutputs outputs = new CapturingOutputs();
    StepRequest request =
        new StepRequest(
            "gitTag",
            Map.of("tag", "v5.0.0"),
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            log,
            outputs);

    StepResult result = new GitTagStepHandler().execute(request);
    assertTrue(result.isSuccess(), log.text());

    assertEquals("v5.0.0", outputs.map.get("tag"), "tag output must reflect the resolved tag");
    assertEquals(
        "true", outputs.map.get("pushed"), "pushed output must be 'true' on default push path");

    Object ref = outputs.map.get("ref");
    assertNotNull(ref, "ref output must be the commit SHA the tag points to");
    String refSha = String.valueOf(ref);
    // git SHA-1 is 40 hex chars; future SHA-256 repos would be 64. Be permissive on width but
    // strict on shape so a regression (publishing the tag name, an empty string, or the tag
    // object SHA) is caught.
    assertTrue(
        refSha.matches("[0-9a-f]{40,64}"),
        "ref output must be a git object hex SHA, got: " + refSha);
    // It must match what git itself resolves locally — proves we captured the right value.
    String expectedSha = gitOut(workDir, "rev-list", "-n", "1", "v5.0.0");
    assertEquals(expectedSha, refSha, "ref output must equal `git rev-list -n 1 <tag>`");
  }

  @Test
  void publishesPushedFalseWhenPushDisabled() throws Exception {
    CapturingLog log = new CapturingLog();
    CapturingOutputs outputs = new CapturingOutputs();
    StepRequest request =
        new StepRequest(
            "gitTag",
            Map.of("tag", "v5.1.0", "push", false),
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            log,
            outputs);

    StepResult result = new GitTagStepHandler().execute(request);
    assertTrue(result.isSuccess(), log.text());

    assertEquals("v5.1.0", outputs.map.get("tag"));
    assertEquals(
        "false",
        outputs.map.get("pushed"),
        "pushed output must reflect the actual push:false arg, not the default");
    assertNotNull(outputs.map.get("ref"), "ref must still resolve from the local tag");
  }

  @Test
  void doesNotPublishOutputsOnFailurePath() throws Exception {
    // Spec contract: failed steps have no semantic outputs. We trigger failure via the
    // validation path (a tag name with whitespace) — guarantees git is never invoked, so any
    // outputs in the sink would be a pure leak from the handler.
    CapturingLog log = new CapturingLog();
    CapturingOutputs outputs = new CapturingOutputs();
    StepRequest request =
        new StepRequest(
            "gitTag",
            Map.of("tag", "v6 with space"),
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            log,
            outputs);

    StepResult result = new GitTagStepHandler().execute(request);
    assertFalse(result.isSuccess(), "must fail on malformed tag");
    assertTrue(outputs.map.isEmpty(), "failed step must not publish outputs, got: " + outputs.map);
  }

  @Test
  void descriptorDeclaresTagAsScalarShorthandKey() {
    // design/42 §4.6 regression: `gitTag: v1.2.3` parses to {value: "v1.2.3"} and the worker
    // resolves `value` → `tag` via the descriptor's scalarShorthandKey.
    assertEquals("tag", new GitTagStepHandler().descriptor().scalarShorthandKey());
  }
}
