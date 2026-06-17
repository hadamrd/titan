package io.adaptiq.titan.flow;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.model.CredentialBinding;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SPI: resolves a step's declarative credential bindings into the sealed bundle that travels in the
 * {@code EXECUTE_COMMAND} payload (design/39, design/41).
 *
 * <p>In {@code titan-server} this is implemented by {@code CredentialResolver}; a production
 * implementation may call Vault or the standalone secrets store; tests inject a no-op stub.
 *
 * <p>Returning {@link Resolved#EMPTY} signals "no credentials to seal" — the orchestrator skips
 * encryption entirely for that step, matching the behaviour when a step declares no bindings.
 */
@FunctionalInterface
public interface CredentialsPort {

  /**
   * An empty resolved bundle — no env, no mask values, no files, no SSH keys. Returned by the no-op
   * stub used in tests and in environments with no credentials configured.
   */
  Resolved EMPTY = new Resolved(Map.of(), List.of(), List.of(), List.of());

  /**
   * Resolve {@code bindings} and {@code sshAgentIds} into a sealed-ready bundle.
   *
   * @throws CredentialResolutionException if any binding cannot be resolved
   */
  @NonNull
  Resolved resolve(@NonNull List<CredentialBinding> bindings, @NonNull List<String> sshAgentIds);

  /**
   * Resolve a {@code secret:<id>} env reference (closes #1094) to its plaintext, or empty if no
   * such credential exists. The id follows the same {@code scope/key} convention as a {@code
   * credentials:} binding id — a bare {@code "gh-token"} resolves under the {@code default} scope;
   * {@code "aws/dev-key"} splits on the {@code /}.
   *
   * <p>Default returns {@link Optional#empty()} — the {@link #noOp} port has no store to look in,
   * so {@code env: { FOO: "secret:..." }} fails the step at dispatch with a clear "not in store"
   * message. Implementations backed by a real store ({@code CredentialResolver} in {@code
   * titan-server}) override this to delegate to the encrypted credentials service.
   *
   * @param id the credential id from the {@code secret:} prefix; never {@code null}; already
   *     validated by {@code EnvValueRef.validate} (no whitespace, no extra colons)
   */
  @NonNull
  default Optional<String> resolveSecretRef(@NonNull String id) {
    return Optional.empty();
  }

  /**
   * Thrown when a binding cannot be resolved. Non-final so the {@code
   * io.adaptiq.titan.credentials.CredentialResolver} implementation can re-export it under its own
   * name without losing the orchestrator's {@code catch
   * (CredentialsPort.CredentialResolutionException)}.
   */
  class CredentialResolutionException extends RuntimeException {
    public CredentialResolutionException(@NonNull String message) {
      super(message);
    }
  }

  /**
   * The resolved credential bundle ready for sealing into the step payload.
   *
   * @param env env entries (variable name → value) to merge into the payload
   * @param maskValues raw secret values the worker masks in the step log
   * @param files secret files the worker materialises into its per-task workspace
   * @param sshAgentKeys SSH keys loaded into a transient {@code ssh-agent} (design/41)
   */
  record Resolved(
      @NonNull Map<String, String> env,
      @NonNull List<String> maskValues,
      @NonNull List<SecretFile> files,
      @NonNull List<SshAgentKey> sshAgentKeys) {

    public boolean isEmpty() {
      return env.isEmpty() && maskValues.isEmpty() && files.isEmpty() && sshAgentKeys.isEmpty();
    }
  }

  /** A secret file produced by a {@code file} or {@code sshKey} binding. */
  record SecretFile(
      @NonNull String variable, @NonNull String fileName, @NonNull String contentBase64) {}

  /** One SSH key for a transient {@code ssh-agent}. */
  record SshAgentKey(@NonNull String privateKey, String passphrase) {}

  /**
   * A stub that always returns empty credentials — suitable for tests and no-credentials deploys.
   */
  static CredentialsPort noOp() {
    return (bindings, sshAgentIds) -> EMPTY;
  }
}
