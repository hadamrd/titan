package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.adaptiq.titan.worker.SshAgentWrapper.Key;
import io.adaptiq.titan.worker.SshAgentWrapper.Prepared;
import io.adaptiq.titan.worker.step.StepRequest;
import io.adaptiq.titan.worker.step.StepResult;
import io.adaptiq.titan.worker.step.builtin.ShellStepHandler;
import io.adaptiq.titan.worker.step.exec.LocalProcessExecutor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link SshAgentWrapper} — the worker side of design/41 §4. The wrapper turns a {@code sh}
 * step's command into a single self-contained shell program: it starts an {@code ssh-agent}, loads
 * every declared key via {@code ssh-add -}, runs the original command, and kills the agent in a
 * {@code trap … EXIT}. Pure-string assertions on the generated wrapper run everywhere; the
 * end-to-end {@code ssh-add}-against-a-real-agent tests self-skip where {@code ssh-agent}/{@code
 * ssh-add} are not on {@code PATH} (a CI host without an SSH toolchain).
 */
class SshAgentWrapperTest {

  // ── wrapper shape (no external tooling needed) ────────────────────────────

  @Test
  void theWrapperStartsAnAgentTrapsItAndRunsTheOriginalCommandLast() throws IOException {
    Path workDir = Files.createTempDirectory("titan-sshagent-shape");
    try {
      Prepared prepared =
          SshAgentWrapper.prepare("git push origin main", List.of(new Key("KEY-0", null)), workDir);
      String script = prepared.wrappedScript();

      assertTrue(
          script.contains("eval \"$(ssh-agent -s)\""),
          "the wrapper must start an ssh-agent: " + script);
      assertTrue(
          script.contains("trap 'ssh-agent -k"),
          "the wrapper must kill the agent in a trap: " + script);
      assertTrue(
          script.contains("ssh-add -t 3600 -"),
          "the wrapper must load the key via ssh-add -: " + script);
      // The original command is the last line of the program.
      assertTrue(
          script.stripTrailing().endsWith("git push origin main"),
          "the original command must run last: " + script);
    } finally {
      deleteRecursively(workDir);
    }
  }

  @Test
  void oneSshAddInvocationIsEmittedPerKey() throws IOException {
    Path workDir = Files.createTempDirectory("titan-sshagent-multi");
    try {
      Prepared prepared =
          SshAgentWrapper.prepare(
              "true",
              List.of(new Key("KEY-0", null), new Key("KEY-1", null), new Key("KEY-2", null)),
              workDir);
      long sshAddCount =
          prepared.wrappedScript().lines().filter(l -> l.contains("ssh-add -t 3600 -")).count();
      assertEquals(3, sshAddCount, "one ssh-add per key");
    } finally {
      deleteRecursively(workDir);
    }
  }

  @Test
  void keysArePassedAsTitanSshKeyEnvVarsNeverInArgv() throws IOException {
    Path workDir = Files.createTempDirectory("titan-sshagent-env");
    try {
      String secretKeyText = "-----BEGIN OPENSSH PRIVATE KEY-----SECRET-MATERIAL";
      Prepared prepared =
          SshAgentWrapper.prepare("true", List.of(new Key(secretKeyText, null)), workDir);

      // The key text reaches the wrapper only through a TITAN_SSH_KEY_* env var.
      assertEquals(secretKeyText, prepared.keyEnv().get("TITAN_SSH_KEY_0"));
      // The key material is NEVER in the script text (which becomes a process argument).
      assertFalse(
          prepared.wrappedScript().contains("SECRET-MATERIAL"),
          "the private key must never appear in the wrapper script (argv): "
              + prepared.wrappedScript());
      // The wrapper references the env var, not the literal key.
      assertTrue(
          prepared.wrappedScript().contains("$TITAN_SSH_KEY_0"),
          "the wrapper must read the key from its env var: " + prepared.wrappedScript());
    } finally {
      deleteRecursively(workDir);
    }
  }

  @Test
  void aPassphraseProtectedKeyEmitsAOneShotAskpassHelper() throws IOException {
    Path workDir = Files.createTempDirectory("titan-sshagent-askpass");
    try {
      Prepared prepared =
          SshAgentWrapper.prepare("true", List.of(new Key("KEY-0", "the-passphrase")), workDir);

      assertEquals(
          1, prepared.askpassHelpers().size(), "a protected key needs an SSH_ASKPASS helper");
      assertTrue(
          Files.exists(prepared.askpassHelpers().get(0)),
          "the askpass helper must be written to disk");
      assertTrue(
          prepared.wrappedScript().contains("SSH_ASKPASS="),
          "the wrapper must point ssh-add at the askpass helper: " + prepared.wrappedScript());

      // cleanup removes the one-shot helper (design/41 §4).
      SshAgentWrapper.cleanup(prepared.askpassHelpers());
      assertFalse(
          Files.exists(prepared.askpassHelpers().get(0)),
          "cleanup must delete the one-shot askpass helper");
    } finally {
      deleteRecursively(workDir);
    }
  }

  @Test
  void anUnprotectedKeyEmitsNoAskpassHelper() throws IOException {
    Path workDir = Files.createTempDirectory("titan-sshagent-noaskpass");
    try {
      Prepared prepared = SshAgentWrapper.prepare("true", List.of(new Key("KEY-0", null)), workDir);
      assertTrue(
          prepared.askpassHelpers().isEmpty(),
          "an unprotected key (the CI norm) skips the askpass helper");
    } finally {
      deleteRecursively(workDir);
    }
  }

  @Test
  void readKeysReturnsEmptyWhenSshAgentKeysIsAbsent() throws IOException {
    com.fasterxml.jackson.databind.JsonNode bundle =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"env\":{}}");
    assertTrue(
        SshAgentWrapper.readKeys(bundle).isEmpty(),
        "absent sshAgentKeys is the common no-sshAgent case");
  }

  @Test
  void readKeysParsesTheSshAgentKeysArray() throws IOException {
    String json =
        """
                {"sshAgentKeys": [
                  {"privateKey": "KEY-A", "passphrase": null},
                  {"privateKey": "KEY-B", "passphrase": "pw"}
                ]}""";
    List<Key> keys =
        SshAgentWrapper.readKeys(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json));
    assertEquals(2, keys.size());
    assertEquals("KEY-A", keys.get(0).privateKey());
    assertFalse(keys.get(0).hasPassphrase());
    assertEquals("KEY-B", keys.get(1).privateKey());
    assertTrue(keys.get(1).hasPassphrase());
  }

  // ── end-to-end: the agent really carries the key ──────────────────────────

  @Test
  void anEndToEndWrappedStepLoadsTheKeyIntoARealAgent() throws Exception {
    // The wrapped program is a genuine multi-line POSIX script with `$(…)` command
    // substitution. Windows' ProcessBuilder re-quotes such an `sh -c <arg>` argument and
    // mangles it (the worker runs on a POSIX host by definition); skip there.
    assumeTrue(
        !System.getProperty("os.name", "").toLowerCase().contains("win"),
        "Windows host mangles a multi-line `sh -c` argv — skipping the e2e sshAgent test");
    assumeTrue(shAvailable(), "POSIX sh not on PATH — skipping the end-to-end sshAgent test");
    assumeTrue(
        sshAgentAvailable(),
        "ssh-agent/ssh-add not on PATH — skipping the end-to-end sshAgent test");

    Path workDir = Files.createTempDirectory("titan-sshagent-e2e");
    try {
      Path keyDir = Files.createDirectory(workDir.resolve("keys"));
      Path keyFile = keyDir.resolve("id_titan");
      assumeTrue(keygen(keyFile), "ssh-keygen not available — skipping the e2e sshAgent test");
      String privateKey = Files.readString(keyFile, StandardCharsets.UTF_8);

      // The wrapped command succeeds ONLY because the agent has the key: `ssh-add -l`
      // exits non-zero when the agent holds no identities.
      Prepared prepared =
          SshAgentWrapper.prepare(
              "ssh-add -l >/dev/null && echo TITAN_AGENT_HAS_KEY",
              List.of(new Key(privateKey, null)),
              workDir);

      CapturingLog log = new CapturingLog();
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("script", prepared.wrappedScript());
      Map<String, String> env = new LinkedHashMap<>(prepared.keyEnv());

      StepResult result =
          new ShellStepHandler()
              .execute(
                  new StepRequest(
                      "sh",
                      args,
                      workDir,
                      env,
                      1L,
                      "node-1",
                      null,
                      new LocalProcessExecutor(),
                      log,
                      new CapturingOutputs()));

      assertTrue(
          result.isSuccess(), "the wrapped step must succeed with the agent loaded: " + log.text());
      assertTrue(
          log.text().contains("TITAN_AGENT_HAS_KEY"),
          "ssh-add -l must see the loaded identity: " + log.text());
    } finally {
      deleteRecursively(workDir);
    }
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private static boolean shAvailable() {
    try {
      return new ProcessBuilder("sh", "-c", "exit 0").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static boolean sshAgentAvailable() {
    return onPath("ssh-agent", "-h") && onPath("ssh-add", "-h");
  }

  /** A tool is "on PATH" if it can be launched at all — the help flag exit code is irrelevant. */
  private static boolean onPath(String... cmd) {
    try {
      new ProcessBuilder(cmd)
          .redirectErrorStream(true)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor();
      return true;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Generate an unprotected ed25519 keypair at {@code keyFile}. Returns false if ssh-keygen is
   * absent.
   */
  private static boolean keygen(Path keyFile) {
    try {
      int exit =
          new ProcessBuilder(
                  "ssh-keygen",
                  "-t",
                  "ed25519",
                  "-N",
                  "",
                  "-f",
                  keyFile.toString(),
                  "-C",
                  "titan-test")
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start()
              .waitFor();
      return exit == 0 && Files.exists(keyFile);
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void deleteRecursively(Path root) {
    try {
      if (!Files.exists(root)) {
        return;
      }
      Files.walk(root)
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException ignored) {
                  // best-effort
                }
              });
    } catch (IOException ignored) {
      // best-effort
    }
  }

  /** A thread-safe {@link io.adaptiq.titan.worker.step.LogSink} recording every line. */
  static final class CapturingLog implements io.adaptiq.titan.worker.step.LogSink {
    final List<String> lines = new CopyOnWriteArrayList<>();

    @Override
    public void line(String stream, String text) {
      lines.add(stream + ": " + text);
    }

    String text() {
      return String.join("\n", lines);
    }
  }

  /** An {@link io.adaptiq.titan.worker.step.OutputSink} that discards outputs. */
  static final class CapturingOutputs implements io.adaptiq.titan.worker.step.OutputSink {
    @Override
    public void put(String key, Object value) {
      // not asserted here
    }
  }
}
