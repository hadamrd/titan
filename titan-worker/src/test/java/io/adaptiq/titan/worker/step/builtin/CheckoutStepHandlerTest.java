package io.adaptiq.titan.worker.step.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * {@link GitStepHandler} bound to the {@code checkout} id — the generic SCM alias (design/32 §9).
 *
 * <p>{@code checkout} delegates to the same implementation as {@code git}; this test confirms the
 * alias registers under its own id, advertises a {@code checkout} descriptor, and clones the same
 * local {@code file://} fixture. It runs the shared TCK so the alias is itself a conformant handler
 * — not a half-wired second entry point.
 */
class CheckoutStepHandlerTest extends StepHandlerTck {

  private static String fixtureRepoUrl;

  @BeforeAll
  static void createFixtureRepo(@TempDir Path fixtureRoot) throws Exception {
    assumeTrue(gitAvailable(), "git CLI not on PATH — skipping the checkout-handler tests");

    Path repo = fixtureRoot.resolve("upstream");
    Files.createDirectories(repo);
    git(repo, "init", "--initial-branch=main");
    git(repo, "config", "user.email", "tck@titan.test");
    git(repo, "config", "user.name", "Titan TCK");
    Files.writeString(repo.resolve("README.md"), "checkout-fixture\n", StandardCharsets.UTF_8);
    git(repo, "add", "README.md");
    git(repo, "commit", "-m", "initial commit");
    fixtureRepoUrl = repo.toUri().toString();
  }

  @BeforeEach
  void requireGit() {
    assumeTrue(gitAvailable(), "git CLI not on PATH — skipping the checkout-handler tests");
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
    if (p.waitFor() != 0) {
      throw new IllegalStateException("git " + String.join(" ", args) + " failed");
    }
  }

  @Override
  protected StepHandler newHandler() {
    return new GitStepHandler(GitStepHandler.CHECKOUT);
  }

  @Override
  protected StepExecutor newExecutor() {
    return new LocalProcessExecutor();
  }

  @Override
  protected Map<String, Object> validArguments() {
    return Map.of("url", fixtureRepoUrl);
  }

  @Test
  void registersUnderTheCheckoutId() {
    StepHandler handler = new GitStepHandler(GitStepHandler.CHECKOUT);
    assertEquals("checkout", handler.descriptorId());
    assertEquals("checkout", handler.descriptor().descriptorId());
  }

  @Test
  void rejectsAnUnknownId() {
    // The handler serves only git/checkout — a typo'd id is a misconfiguration, not silent.
    assertThrows(IllegalArgumentException.class, () -> new GitStepHandler("svn"));
  }

  @Test
  void clonesTheRepositoryViaTheAlias() throws Exception {
    CapturingLog log = new CapturingLog();
    StepRequest request =
        new StepRequest(
            "checkout",
            Map.of("url", fixtureRepoUrl),
            workDir,
            Map.of(),
            1L,
            "node-1",
            null,
            new LocalProcessExecutor(),
            log,
            new CapturingOutputs());

    StepResult result = new GitStepHandler(GitStepHandler.CHECKOUT).execute(request);

    assertTrue(result.isSuccess(), log.text());
    assertTrue(
        Files.exists(workDir.resolve("README.md")),
        "the checkout alias must clone the working tree: " + log.text());
  }
}
