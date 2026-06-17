package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link GitStepHandler} — the shared TCK plus {@code git}-specific assertions (Chunk 32E —
 * design/32 §9, §12 D5).
 *
 * <p>Every test clones a <strong>local {@code file://} fixture repository</strong> created in
 * {@link #createFixtureRepo} — no network dependence (the rig caught network-flaky tests before).
 * On a host without the {@code git} CLI on {@code PATH} the whole class self-skips: a worker that
 * runs {@code git} steps has {@code git} installed by definition (the rig's worker image does — see
 * {@code rig/local/Dockerfile.worker}).
 */
class GitStepHandlerTest extends StepHandlerTck {

  /** A {@code file://} URL to a real git repo, created once for the whole class. */
  private static String fixtureRepoUrl;

  /** The fixture repo on disk — its working tree, so a HEAD commit / branch exist. */
  private static Path fixtureRepoDir;

  @BeforeAll
  static void createFixtureRepo(@TempDir Path fixtureRoot) throws Exception {
    assumeTrue(gitAvailable(), "git CLI not on PATH — skipping the git-handler tests");

    fixtureRepoDir = fixtureRoot.resolve("upstream");
    Files.createDirectories(fixtureRepoDir);

    // A real repository with one commit on the default branch and a feature branch, so the
    // tests exercise both the default-branch clone and an explicit `branch:`.
    git(fixtureRepoDir, "init", "--initial-branch=main");
    git(fixtureRepoDir, "config", "user.email", "tck@titan.test");
    git(fixtureRepoDir, "config", "user.name", "Titan TCK");
    Files.writeString(
        fixtureRepoDir.resolve("README.md"), "titan-git-fixture\n", StandardCharsets.UTF_8);
    git(fixtureRepoDir, "add", "README.md");
    git(fixtureRepoDir, "commit", "-m", "initial commit");

    git(fixtureRepoDir, "checkout", "-b", "feature/x");
    Files.writeString(
        fixtureRepoDir.resolve("FEATURE.md"), "on the feature branch\n", StandardCharsets.UTF_8);
    git(fixtureRepoDir, "add", "FEATURE.md");
    git(fixtureRepoDir, "commit", "-m", "feature commit");
    git(fixtureRepoDir, "checkout", "main");

    // file:// so a clone exercises the real `git clone` transport with zero network.
    fixtureRepoUrl = fixtureRepoDir.toUri().toString();
  }

  @BeforeEach
  void requireGit() {
    assumeTrue(gitAvailable(), "git CLI not on PATH — skipping the git-handler tests");
  }

  private static boolean gitAvailable() {
    try {
      return new ProcessBuilder("git", "--version").redirectErrorStream(true).start().waitFor()
          == 0;
    } catch (Exception e) {
      return false;
    }
  }

  /** Run a git command in {@code dir}, failing the test if it does not exit 0. */
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

  // ── TCK wiring ────────────────────────────────────────────────────────

  @Override
  protected StepHandler newHandler() {
    return new GitStepHandler();
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("url", fixtureRepoUrl);
  }

  private StepRequest requestWith(Map<String, Object> arguments, CapturingLog log) {
    return new StepRequest(
        "git",
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

  // ── git-specific behaviour ────────────────────────────────────────────

  @Test
  void clonesTheRepositoryIntoTheWorkspace() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitStepHandler().execute(requestWith(Map.of("url", fixtureRepoUrl), log));

    assertTrue(result.isSuccess(), log.text());
    assertEquals(0, result.exitCode());
    assertTrue(
        Files.exists(workDir.resolve("README.md")),
        "the cloned working tree must be in the workspace: " + log.text());
    assertTrue(Files.isDirectory(workDir.resolve(".git")), ".git must be present");
  }

  @Test
  void acceptsTheScalarShorthandUnderValue() throws Exception {
    // `- git: file:///…` parses to arguments {value: <url>}; the handler accepts either key.
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitStepHandler().execute(requestWith(Map.of("value", fixtureRepoUrl), log));

    assertTrue(result.isSuccess(), log.text());
    assertTrue(Files.exists(workDir.resolve("README.md")), "shorthand clone must populate");
  }

  @Test
  void clonesAnExplicitBranch() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitStepHandler()
            .execute(requestWith(Map.of("url", fixtureRepoUrl, "branch", "feature/x"), log));

    assertTrue(result.isSuccess(), log.text());
    assertTrue(
        Files.exists(workDir.resolve("FEATURE.md")),
        "the feature branch's file must be checked out");
  }

  @Test
  void shallowByDefault() throws Exception {
    // A shallow clone leaves a .git/shallow marker; default behaviour, no `shallow:` arg.
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitStepHandler().execute(requestWith(Map.of("url", fixtureRepoUrl), log));

    assertTrue(result.isSuccess(), log.text());
    assertTrue(
        Files.exists(workDir.resolve(".git/shallow")),
        "the default clone must be shallow (--depth 1)");
  }

  @Test
  void fullHistoryWhenShallowIsFalse() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitStepHandler()
            .execute(requestWith(Map.of("url", fixtureRepoUrl, "shallow", false), log));

    assertTrue(result.isSuccess(), log.text());
    assertFalse(
        Files.exists(workDir.resolve(".git/shallow")), "shallow:false must produce a full clone");
  }

  @Test
  void descriptorDeclaresTheScalarShorthandKey() {
    // design/42 §4.6 regression: the descriptor must declare value -> url so the worker's
    // normalization (and StepArgumentValidator) honour the `git: <url>` shorthand payload.
    assertEquals("url", new GitStepHandler().descriptor().scalarShorthandKey());
  }

  @Test
  void aMissingUrlFailsTheStep() throws Exception {
    StepResult result = new GitStepHandler().execute(request(Map.of()));
    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("url"), result.message());
  }

  @Test
  void aBadUrlFailsTheStepWithACloneExitCode() throws Exception {
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitStepHandler().execute(requestWith(Map.of("url", "file:///no/such/titan/repo"), log));

    assertFalse(result.isSuccess());
    assertTrue(result.message().contains("git clone failed"), result.message());
  }

  @Test
  void reCloningIntoANonEmptyWorkspaceSucceeds() throws Exception {
    // Idempotency (design/30 caveat 3): a re-delivered task re-runs from scratch. The handler
    // clears the checkout dir first, so a second clone over a populated workspace works.
    Files.writeString(workDir.resolve("stale.txt"), "left by a reaped attempt");
    CapturingLog log = new CapturingLog();

    StepResult first =
        new GitStepHandler().execute(requestWith(Map.of("url", fixtureRepoUrl), log));
    assertTrue(first.isSuccess(), log.text());
    StepResult second =
        new GitStepHandler().execute(requestWith(Map.of("url", fixtureRepoUrl), log));

    assertTrue(
        second.isSuccess(), "a re-clone over an existing checkout must succeed: " + log.text());
    assertTrue(Files.exists(workDir.resolve("README.md")));
    assertFalse(
        Files.exists(workDir.resolve("stale.txt")),
        "the stale file from a prior attempt must be cleared");
  }

  @Test
  void aSuppliedCredentialsIdIsAcceptedAndLogged() throws Exception {
    // The credentials seam (design/32 §12 D6): credentialsId is accepted by the contract but
    // resolution is owned by the withCredentials workstream — it must be logged, never
    // silently honoured, and must not break a public clone.
    CapturingLog log = new CapturingLog();
    StepResult result =
        new GitStepHandler()
            .execute(requestWith(Map.of("url", fixtureRepoUrl, "credentialsId", "my-creds"), log));

    assertTrue(result.isSuccess(), log.text());
    assertTrue(
        log.text().contains("credentialsId 'my-creds'"),
        "a supplied credentialsId must be surfaced in the log: " + log.text());
    assertTrue(
        log.text().contains("not yet wired"),
        "the log must state credentials are not yet honoured: " + log.text());
  }
}
