package io.adaptiq.titan.worker.step;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The mutable carrier an {@link ExecutionAugmenter} decorates (design/42 §4.9).
 *
 * <p>It exposes exactly what the two built-in augmenters — {@code credentials} (design/39) and
 * {@code sshAgent} (design/41) — need, and no more. The worker builds one of these per task, loops
 * the discovered augmenters over it, then reads the decorated state back out to assemble the {@link
 * StepRequest}. After the step, the worker runs every {@linkplain #postStepActions() registered
 * post-step action} in a {@code finally} block.
 *
 * <p>Kept JDK-only — no Jackson, no crypto types. An augmenter that needs the unsealed credential
 * bundle reads it through {@link #credentialBundle()} as already-parsed plain data ({@link Map}s,
 * {@link List}s, {@link String}s); bundle decryption and JSON parsing stay inside the worker.
 */
public final class StepExecutionContext {

  private final String descriptorId;
  private final Map<String, Object> arguments;
  private final Map<String, String> env;
  private final Path workspace;
  private final long buildId;
  private final String nodeId;
  private final Map<String, Object> credentialBundle;
  private final List<String> maskValues = new ArrayList<>();
  private final List<Runnable> postStepActions = new ArrayList<>();
  private CommandWrapper commandWrapper;

  /**
   * @param descriptorId the step type — e.g. {@code sh}, {@code script} (an augmenter may key off
   *     it, as the {@code sshAgent} one does its single-shell guard)
   * @param arguments the step's mutable argument map — an augmenter may rewrite an entry (the
   *     {@code sshAgent} augmenter rewrites the {@code script}/{@code value} command in place)
   * @param env the mutable step environment — an augmenter layers credential / key env onto it
   * @param workspace the per-task workspace directory — where an augmenter materialises files
   * @param buildId the build id
   * @param nodeId the node id
   * @param credentialBundle the unsealed credential bundle as plain JDK data, never {@code null}
   *     (an empty map for a step that declared no {@code credentials:})
   */
  public StepExecutionContext(
      String descriptorId,
      Map<String, Object> arguments,
      Map<String, String> env,
      Path workspace,
      long buildId,
      String nodeId,
      Map<String, Object> credentialBundle) {
    this.descriptorId = Objects.requireNonNull(descriptorId, "descriptorId");
    this.arguments = Objects.requireNonNull(arguments, "arguments");
    this.env = Objects.requireNonNull(env, "env");
    this.workspace = Objects.requireNonNull(workspace, "workspace");
    this.buildId = buildId;
    this.nodeId = nodeId == null ? "" : nodeId;
    this.credentialBundle = Objects.requireNonNull(credentialBundle, "credentialBundle");
  }

  /** The step type. */
  public String descriptorId() {
    return descriptorId;
  }

  /** The step's mutable argument map — an augmenter may rewrite an entry. */
  public Map<String, Object> arguments() {
    return arguments;
  }

  /** The step's mutable environment — an augmenter layers env onto it. */
  public Map<String, String> env() {
    return env;
  }

  /** The per-task workspace directory. */
  public Path workspace() {
    return workspace;
  }

  /** The build id. */
  public long buildId() {
    return buildId;
  }

  /** The node id. */
  public String nodeId() {
    return nodeId;
  }

  /**
   * The unsealed credential bundle as plain JDK data — {@code {env, maskSecrets, secretFiles,
   * sshAgentKeys}} mapped to {@link Map}s / {@link List}s / {@link String}s. Never {@code null}; an
   * empty map for a step that declared no {@code credentials:}.
   */
  public Map<String, Object> credentialBundle() {
    return credentialBundle;
  }

  /**
   * Register a secret value to be masked in this step's log. The worker collects every registered
   * value and wraps the step's {@code LogSink} once (a {@code MaskingLogSink}).
   */
  public void addMaskValue(String value) {
    if (value != null && !value.isEmpty()) {
      maskValues.add(value);
    }
  }

  /** Every value registered for masking, in registration order. */
  public List<String> maskValues() {
    return maskValues;
  }

  /**
   * Register an action to run after the step completes — in the worker's {@code finally} block,
   * whether the step succeeded, failed, or threw. Used for secret-file wipe (design/39) and
   * askpass-helper cleanup (design/41). Actions run in registration order; a throwing action does
   * not abort the rest.
   */
  public void registerPostStepAction(Runnable action) {
    if (action != null) {
      postStepActions.add(action);
    }
  }

  /** Every registered post-step cleanup action, in registration order. */
  public List<Runnable> postStepActions() {
    return postStepActions;
  }

  /**
   * Install the hook that wraps the step command. The {@code sshAgent} augmenter uses this to
   * rewrite a {@code sh} step's command into an ssh-agent lifecycle. At most one wrapper is
   * expected per step; a later call replaces an earlier one.
   */
  public void setCommandWrapper(CommandWrapper wrapper) {
    this.commandWrapper = wrapper;
  }

  /** The installed command wrapper, or {@code null} if no augmenter wrapped the command. */
  public CommandWrapper commandWrapper() {
    return commandWrapper;
  }

  /**
   * Abort the step from inside an augmenter — the worker catches this and fails the step with
   * {@code message}, exactly as a handler-returned {@link StepResult#failed(String)} would, before
   * any step side effect.
   */
  public void abort(String message) {
    throw new AugmentAbort(message);
  }

  /**
   * A command-rewriting hook (design/42 §4.9). The {@code sshAgent} augmenter installs one to wrap
   * a {@code sh} step's shell command in an ssh-agent lifecycle.
   */
  @FunctionalInterface
  public interface CommandWrapper {
    /**
     * Rewrite a step's shell command.
     *
     * @param original the step's original shell command
     * @return the wrapped command to run instead
     */
    String wrap(String original);
  }

  /**
   * Thrown by {@link #abort(String)} to fail the step from inside an augmenter. The worker catches
   * it and records a clean failure; it never escapes the worker.
   */
  public static final class AugmentAbort extends RuntimeException {
    private static final long serialVersionUID = 1L;

    AugmentAbort(String message) {
      super(message);
    }
  }
}
