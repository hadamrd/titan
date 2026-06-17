package io.adaptiq.titan.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wraps a {@code sh} / {@code script} step's shell command with an {@code ssh-agent} lifecycle
 * (design/41 §4).
 *
 * <p>When a step declares {@code sshAgent:}, the controller resolves the named {@code
 * SSHUserPrivateKey} credentials and seals the private-key text (plus optional passphrase) into the
 * credential bundle under {@code sshAgentKeys} (design/41 §3). This class consumes that unsealed
 * array and produces a single self-contained shell program: it starts an {@code ssh-agent}, loads
 * every key, runs the original step command, and kills the agent in a {@code trap … EXIT} — start,
 * load, run, kill, all in <strong>one</strong> {@code sh -c} invocation.
 *
 * <p>One invocation is the point (design/41 §4): the container executor runs each executor call as
 * a fresh {@code docker run}, so a long-lived background agent would die with its container.
 * Wrapping makes the agent's lifetime exactly the step's shell process — the only construction that
 * behaves identically under the local and the container executor.
 *
 * <p>Keys never reach {@code argv}: each private key is passed to the wrapper as an environment
 * variable ({@code TITAN_SSH_KEY_0}, {@code …_1}, …) set in the step process env, and the wrapper
 * pipes it into {@code ssh-add -} via stdin. A passphrase-protected key is loaded with a one-shot
 * {@code SSH_ASKPASS} helper written into the per-task workspace and deleted immediately after.
 */
public final class SshAgentWrapper {

  /** The env-var prefix carrying private-key text to the generated wrapper. */
  static final String KEY_ENV_PREFIX = "TITAN_SSH_KEY_";

  /** The sub-directory of the per-task workspace that holds transient askpass helpers. */
  private static final String ASKPASS_DIR = ".titan-sshagent";

  private SshAgentWrapper() {}

  /** A parsed {@code sshAgentKeys} entry — the private-key text and an optional passphrase. */
  public record Key(String privateKey, String passphrase) {
    public boolean hasPassphrase() {
      return passphrase != null && !passphrase.isEmpty();
    }
  }

  /**
   * Read the optional {@code sshAgentKeys} array from an unsealed credential bundle. Absent,
   * non-array, or empty all yield an empty list — the common no-{@code sshAgent} case.
   */
  public static List<Key> readKeys(JsonNode credentials) {
    List<Key> keys = new ArrayList<>();
    JsonNode node = credentials.get("sshAgentKeys");
    if (node == null || !node.isArray()) {
      return keys;
    }
    for (JsonNode entry : node) {
      String privateKey = entry.path("privateKey").asText(null);
      if (privateKey == null || privateKey.isBlank()) {
        continue;
      }
      JsonNode pass = entry.get("passphrase");
      String passphrase = (pass != null && pass.isTextual()) ? pass.asText() : null;
      keys.add(new Key(privateKey, passphrase));
    }
    return keys;
  }

  /**
   * The outcome of preparing the ssh-agent wrapper for a step.
   *
   * @param wrappedScript the original step command wrapped in the agent lifecycle — to be run as
   *     {@code sh -c <wrappedScript>}
   * @param keyEnv extra env vars ({@code TITAN_SSH_KEY_0}, …) carrying the private keys; these are
   *     layered onto the step process env so the wrapper, but never {@code argv}, sees them
   * @param askpassHelpers helper scripts written into the workspace — to be deleted after the step
   */
  public record Prepared(
      String wrappedScript, Map<String, String> keyEnv, List<Path> askpassHelpers) {}

  /**
   * Build the wrapped shell program for a step.
   *
   * @param originalScript the step's original shell command (the {@code sh} script / {@code script}
   *     body's shell payload)
   * @param keys the unsealed {@code sshAgentKeys}; must be non-empty (the caller no-ops on empty)
   * @param workDir the step's working directory — also the container bind-mount root, so askpass
   *     helpers written here are reachable by a relative path under both executors
   */
  public static Prepared prepare(String originalScript, List<Key> keys, Path workDir)
      throws IOException {
    Map<String, String> keyEnv = new java.util.LinkedHashMap<>();
    List<Path> helpers = new ArrayList<>();
    StringBuilder sh = new StringBuilder();

    // ssh-agent's whole life is this one shell process: start it, kill it on ANY exit
    // (success, failure, signal) via the EXIT trap. The trap is the primary teardown; the
    // per-key `-t 3600` cap is only a backstop (design/41 §4).
    sh.append(
        "eval \"$(ssh-agent -s)\" >/dev/null 2>&1"
            + " || { echo \"titan: ssh-agent not available\" >&2; exit 1; }\n");
    sh.append("trap 'ssh-agent -k >/dev/null 2>&1' EXIT\n");

    Path askpassDir = workDir.resolve(ASKPASS_DIR);
    for (int i = 0; i < keys.size(); i++) {
      Key key = keys.get(i);
      String keyVar = KEY_ENV_PREFIX + i;
      keyEnv.put(keyVar, key.privateKey());

      if (key.hasPassphrase()) {
        Files.createDirectories(askpassDir);
        String helperName = "askpass-" + i + ".sh";
        Path helper = askpassDir.resolve(helperName);
        // The askpass helper just echoes the passphrase. It is one-shot: written here,
        // deleted by the caller right after the step. The passphrase is in maskSecrets
        // (design/41 §3) so any accidental echo is already redacted by MaskingLogSink.
        Files.write(
            helper,
            ("#!/bin/sh\nprintf '%s' '" + shellSingleQuote(key.passphrase()) + "'\n")
                .getBytes(StandardCharsets.UTF_8));
        makeExecutable(helper);
        helpers.add(helper);
        // Reference the helper by a path relative to the working directory: that path is
        // valid both for the local executor (cwd == workDir) and the container executor
        // (workDir is bind-mounted at the container's working dir).
        String helperPath = "./" + ASKPASS_DIR + "/" + helperName;
        // SSH_ASKPASS_REQUIRE=force makes ssh-add use the askpass program even with no
        // tty/DISPLAY; setsid detaches from any controlling terminal so ssh-add cannot
        // fall back to an interactive prompt. stdin still carries the key bytes.
        sh.append("SSH_ASKPASS='")
            .append(helperPath)
            .append("' SSH_ASKPASS_REQUIRE=force DISPLAY=:0 ")
            .append("setsid -w ssh-add -t 3600 - <<TITAN_SSH_EOF_")
            .append(i)
            .append(" >/dev/null 2>&1")
            .append(" || { echo \"titan: ssh-add failed for key ")
            .append(i)
            .append("\" >&2; exit 1; }\n")
            .append("$")
            .append(keyVar)
            .append('\n')
            .append("TITAN_SSH_EOF_")
            .append(i)
            .append('\n');
      } else {
        // Unprotected key (the CI norm): pipe the env var straight into `ssh-add -`.
        sh.append("printf '%s\\n' \"$")
            .append(keyVar)
            .append("\" | ssh-add -t 3600 - >/dev/null 2>&1")
            .append(" || { echo \"titan: ssh-add failed for key ")
            .append(i)
            .append("\" >&2; exit 1; }\n");
      }
    }

    // The original step command runs last — only ever reached once every key is loaded.
    sh.append(originalScript).append('\n');
    return new Prepared(sh.toString(), keyEnv, helpers);
  }

  /** Delete the one-shot askpass helpers written by {@link #prepare}. Best-effort. */
  public static void cleanup(List<Path> helpers) {
    for (Path helper : helpers) {
      try {
        if (Files.exists(helper)) {
          long size = Files.size(helper);
          if (size > 0) {
            Files.write(helper, new byte[(int) size]);
          }
        }
        Files.deleteIfExists(helper);
      } catch (IOException ignored) {
        // a worker crash leaves it in the per-task dir, which is reaped wholesale later
      }
    }
  }

  /** Escape a value for embedding inside a single-quoted POSIX shell string. */
  private static String shellSingleQuote(String value) {
    return value.replace("'", "'\\''");
  }

  /** Mark a helper script executable where the filesystem supports POSIX permissions. */
  private static void makeExecutable(Path file) {
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
      // non-POSIX filesystem (Windows host) — the helper is invoked via `sh <path>` anyway
    } catch (IOException ignored) {
      // best-effort
    }
  }
}
