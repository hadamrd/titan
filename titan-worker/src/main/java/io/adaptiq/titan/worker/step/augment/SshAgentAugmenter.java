package io.adaptiq.titan.worker.step.augment;

import io.adaptiq.titan.worker.SshAgentWrapper;
import io.adaptiq.titan.worker.step.ExecutionAugmenter;
import io.adaptiq.titan.worker.step.StepExecutionContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code sshAgent:} execution augmenter (design/41, refactored per design/42 §4.9).
 *
 * <p>A step that declared {@code sshAgent:} carries its resolved private keys in the unsealed
 * credential bundle under {@code sshAgentKeys}. When present, this augmenter wraps the step's shell
 * command with an ssh-agent lifecycle — start, load every key, run, kill in a {@code trap … EXIT},
 * all in one {@code sh -c} invocation — by delegating to {@link SshAgentWrapper}. The keys reach
 * the wrapper as {@code TITAN_SSH_KEY_*} env vars (never argv); the augmenter registers an
 * askpass-helper cleanup as a post-step action.
 *
 * <p>Ordered <em>after</em> {@link CredentialsAugmenter} (design/42 §4.9) so the credential env is
 * already merged. Absent / empty {@code sshAgentKeys} is a pure no-op — the common case.
 *
 * <p>This is the behaviour design/41 bolted inline into {@code TaskExecutor}, moved verbatim behind
 * the SPI — same observable behaviour, modular code.
 */
public final class SshAgentAugmenter implements ExecutionAugmenter {

  /** Runs after {@link CredentialsAugmenter#ORDER} — the wrapper may rely on credential env. */
  public static final int ORDER = 200;

  @Override
  public int order() {
    return ORDER;
  }

  @Override
  public void augment(StepExecutionContext ctx) {
    List<SshAgentWrapper.Key> keys = readKeys(ctx.credentialBundle());
    if (keys.isEmpty()) {
      return; // no sshAgent: — pure no-op (the common case).
    }
    // The parser guarantees sshAgent: only reaches sh/script; defensively, a script step's
    // body is in-process Groovy with no single wrappable shell invocation, so it cannot
    // honour the per-step agent-lifetime contract. Fail visibly rather than drop the keys.
    if (!"sh".equals(ctx.descriptorId())) {
      ctx.abort(
          "sshAgent keys were delivered for a '"
              + ctx.descriptorId()
              + "' step, but the worker can only wrap a single shell invocation "
              + "('sh'); see design/41 §2.1/§4");
      return;
    }
    Map<String, Object> arguments = ctx.arguments();
    String scriptArg = arguments.containsKey("script") ? "script" : "value";
    Object original = arguments.get(scriptArg);
    if (original == null || String.valueOf(original).isBlank()) {
      ctx.abort("sh step with sshAgent has no 'script'");
      return;
    }
    // Install the command-wrapping hook (design/42 §4.9). prepare() produces the wrapped
    // program plus the key env and the one-shot askpass helpers — the latter two are layered
    // onto the context as the wrapper runs, so the worker's env merge and post-step cleanup
    // see them.
    ctx.setCommandWrapper(
        originalScript -> {
          try {
            SshAgentWrapper.Prepared prepared =
                SshAgentWrapper.prepare(originalScript, keys, ctx.workspace());
            ctx.env().putAll(prepared.keyEnv());
            List<java.nio.file.Path> helpers = prepared.askpassHelpers();
            ctx.registerPostStepAction(() -> SshAgentWrapper.cleanup(helpers));
            return prepared.wrappedScript();
          } catch (IOException e) {
            throw new IllegalStateException(
                "could not prepare the ssh-agent wrapper: " + e.getMessage(), e);
          }
        });
  }

  /**
   * Read the optional {@code sshAgentKeys} array from the plain-data unsealed credential bundle.
   * Absent, non-list, or empty all yield an empty list — the common no-{@code sshAgent} case.
   * (Mirrors {@link SshAgentWrapper#readKeys} but reads JDK {@code Map}/{@code List} rather than a
   * Jackson {@code JsonNode}, keeping {@code titan-step-api} JSON-free.)
   */
  private static List<SshAgentWrapper.Key> readKeys(Map<String, Object> bundle) {
    List<SshAgentWrapper.Key> keys = new ArrayList<>();
    Object node = bundle.get("sshAgentKeys");
    if (!(node instanceof List<?> list)) {
      return keys;
    }
    for (Object entry : list) {
      if (!(entry instanceof Map<?, ?> map)) {
        continue;
      }
      Object pk = map.get("privateKey");
      String privateKey = pk == null ? null : String.valueOf(pk);
      if (privateKey == null || privateKey.isBlank()) {
        continue;
      }
      Object pass = map.get("passphrase");
      String passphrase = (pass instanceof String s) ? s : null;
      keys.add(new SshAgentWrapper.Key(privateKey, passphrase));
    }
    return keys;
  }
}
