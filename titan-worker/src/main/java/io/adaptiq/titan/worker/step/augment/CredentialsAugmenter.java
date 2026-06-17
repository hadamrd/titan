package io.adaptiq.titan.worker.step.augment;

import io.adaptiq.titan.worker.step.ExecutionAugmenter;
import io.adaptiq.titan.worker.step.StepExecutionContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code credentials:} execution augmenter (design/39, refactored per design/42 §4.9).
 *
 * <p>The controller resolved this step's credential bindings against the credentials store and
 * sealed them into the task payload; the worker unseals the bundle (AES-256-GCM — that foundational
 * step stays in {@code TaskExecutor}) and hands the plaintext to this augmenter through {@link
 * StepExecutionContext#credentialBundle()}. From the bundle this augmenter:
 *
 * <ul>
 *   <li>layers the bundle's {@code env} onto the step env — a credential variable wins a clash;
 *   <li>materialises {@code secretFiles} ({@code file} / {@code sshKey} bindings) into a {@code
 *       .titan-credentials} directory under the per-task workspace, owner-only where the filesystem
 *       supports it, and binds each file's path to its declared env variable;
 *   <li>registers every {@code maskSecrets} value for masking in this step's log;
 *   <li>registers a post-step action that wipes the materialised secret files (overwrite, then
 *       unlink) the moment the step ends.
 * </ul>
 *
 * <p>This is the behaviour design/39 bolted inline into {@code TaskExecutor}, moved verbatim behind
 * the SPI — same observable behaviour, modular code.
 */
public final class CredentialsAugmenter implements ExecutionAugmenter {

  private static final Logger LOG = LoggerFactory.getLogger(CredentialsAugmenter.class);

  /** Runs before {@link SshAgentAugmenter} — the sshAgent wrapper may rely on credential env. */
  public static final int ORDER = 100;

  @Override
  public int order() {
    return ORDER;
  }

  @Override
  public void augment(StepExecutionContext ctx) {
    Map<String, Object> bundle = ctx.credentialBundle();
    if (bundle.isEmpty()) {
      return; // a step that declared no credentials: — pure no-op.
    }
    mergeCredentialEnv(bundle, ctx.env());
    registerMaskValues(bundle, ctx);
    materialiseSecretFiles(bundle, ctx);
  }

  /** Layer the unsealed credential env onto the step env — a credential variable wins a clash. */
  private static void mergeCredentialEnv(Map<String, Object> bundle, Map<String, String> env) {
    Object credentialEnv = bundle.get("env");
    if (credentialEnv instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> e : map.entrySet()) {
        env.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
      }
    }
  }

  /** Register every {@code maskSecrets} value for masking in this step's log. */
  private static void registerMaskValues(Map<String, Object> bundle, StepExecutionContext ctx) {
    Object node = bundle.get("maskSecrets");
    if (node instanceof List<?> list) {
      for (Object v : list) {
        if (v instanceof String s) {
          ctx.addMaskValue(s);
        }
      }
    }
  }

  /**
   * Materialise the bundle's {@code secretFiles} into {@code .titan-credentials} under the per-task
   * workspace, bind each to its env variable, and register the post-step wipe.
   */
  private static void materialiseSecretFiles(Map<String, Object> bundle, StepExecutionContext ctx) {
    Object files = bundle.get("secretFiles");
    if (!(files instanceof List<?> list) || list.isEmpty()) {
      return;
    }
    List<Path> created = new ArrayList<>();
    try {
      Path dir = ctx.workspace().resolve(".titan-credentials");
      Files.createDirectories(dir);
      for (Object entry : list) {
        if (!(entry instanceof Map<?, ?> file)) {
          continue;
        }
        String variable = asText(file.get("variable"));
        String fileName = asText(file.get("fileName"));
        String contentBase64 = asText(file.get("contentBase64"));
        if (variable == null || fileName == null || contentBase64 == null) {
          continue;
        }
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir)) {
          continue; // a fileName must not escape the credentials dir
        }
        byte[] content = Base64.getDecoder().decode(contentBase64);
        Files.write(target, content);
        try {
          Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
          // a non-POSIX filesystem (Windows) — best-effort
        }
        ctx.env().put(variable, target.toAbsolutePath().toString());
        created.add(target);
      }
    } catch (IOException e) {
      ctx.abort("could not materialise credential files: " + e.getMessage());
    }
    // Wipe the materialised files the moment the step ends (design/39 §3).
    ctx.registerPostStepAction(() -> clearSecretFiles(created));
  }

  /**
   * Wipe the materialised secret files (design/39 §3) — the bytes are overwritten with zeros before
   * the file is unlinked, so the secret is not recoverable from freed blocks.
   */
  private static void clearSecretFiles(List<Path> secretFiles) {
    for (Path file : secretFiles) {
      try {
        if (Files.exists(file)) {
          long size = Files.size(file);
          if (size > 0) {
            Files.write(file, new byte[(int) Math.min(size, Integer.MAX_VALUE)]);
          }
        }
        Files.deleteIfExists(file);
      } catch (Exception e) {
        LOG.warn("could not clear credential file {}: {}", file, e.toString());
      }
    }
  }

  private static String asText(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
