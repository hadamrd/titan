package io.adaptiq.titan.worker.step.augment;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.StepExecutionContext;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 42-T — unit coverage for {@link SshAgentAugmenter} (design/41, refactored per design/42 §4.9).
 * The 42-X agent flagged that no augmenter unit test existed; this is it. The augmenter is
 * exercised directly against a {@link StepExecutionContext} built from a plain-data credential
 * bundle carrying {@code sshAgentKeys}.
 */
class SshAgentAugmenterTest {

  private static StepExecutionContext ctx(
      String descriptorId, Map<String, Object> args, Path workspace, Map<String, Object> bundle) {
    return new StepExecutionContext(
        descriptorId, new HashMap<>(args), new HashMap<>(), workspace, 1L, "node-1", bundle);
  }

  @Test
  void anEmptyBundleIsAPureNoOp(@TempDir Path workspace) {
    StepExecutionContext c = ctx("sh", Map.of("script", "echo hi"), workspace, Map.of());
    new SshAgentAugmenter().augment(c);
    assertNull(c.commandWrapper(), "no sshAgent: ⇒ no command wrapper installed");
    assertTrue(c.postStepActions().isEmpty(), "no sshAgent: ⇒ no cleanup registered");
  }

  @Test
  void aBundleWithoutSshAgentKeysIsANoOp(@TempDir Path workspace) {
    StepExecutionContext c =
        ctx("sh", Map.of("script", "echo hi"), workspace, Map.of("env", Map.of("X", "y")));
    new SshAgentAugmenter().augment(c);
    assertNull(c.commandWrapper(), "a bundle with no sshAgentKeys must not wrap the command");
  }

  @Test
  void sshAgentKeysOnANonShStepAbortTheStep(@TempDir Path workspace) {
    // design/41 §2.1 — only a single shell invocation can honour the per-step agent lifetime;
    // sshAgent keys delivered to a 'script' step must fail visibly, not silently drop the keys.
    Map<String, Object> bundle =
        Map.of("sshAgentKeys", List.of(Map.of("privateKey", "-----BEGIN KEY-----")));
    StepExecutionContext c = ctx("script", Map.of("body", "x"), workspace, bundle);
    StepExecutionContext.AugmentAbort ex =
        assertThrows(
            StepExecutionContext.AugmentAbort.class, () -> new SshAgentAugmenter().augment(c));
    assertTrue(ex.getMessage().contains("script"), ex.getMessage());
    assertTrue(ex.getMessage().contains("sshAgent"), ex.getMessage());
  }

  @Test
  void anShStepWithSshAgentKeysButNoScriptAborts(@TempDir Path workspace) {
    Map<String, Object> bundle =
        Map.of("sshAgentKeys", List.of(Map.of("privateKey", "-----BEGIN KEY-----")));
    StepExecutionContext c = ctx("sh", Map.of(), workspace, bundle);
    StepExecutionContext.AugmentAbort ex =
        assertThrows(
            StepExecutionContext.AugmentAbort.class, () -> new SshAgentAugmenter().augment(c));
    assertTrue(ex.getMessage().contains("script"), ex.getMessage());
  }

  @Test
  void anShStepWithSshAgentKeysInstallsACommandWrapper(@TempDir Path workspace) {
    Map<String, Object> bundle =
        Map.of(
            "sshAgentKeys",
            List.of(
                Map.of("privateKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END-----")));
    StepExecutionContext c = ctx("sh", Map.of("script", "git push"), workspace, bundle);
    new SshAgentAugmenter().augment(c);
    assertTrue(
        c.commandWrapper() != null,
        "an sh step with sshAgent keys must get a command wrapper installed (design/42 §4.9)");
  }

  @Test
  void theInstalledWrapperWrapsTheCommandInAnSshAgentLifecycle(@TempDir Path workspace) {
    Map<String, Object> bundle =
        Map.of(
            "sshAgentKeys",
            List.of(
                Map.of("privateKey", "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END-----")));
    StepExecutionContext c = ctx("sh", Map.of("script", "git push origin main"), workspace, bundle);
    new SshAgentAugmenter().augment(c);

    String wrapped = c.commandWrapper().wrap("git push origin main");
    assertTrue(wrapped.contains("ssh-agent"), "the wrapped command must start an ssh-agent");
    assertTrue(
        wrapped.contains("git push origin main"),
        "the wrapped command must still run the original command");
  }
}
