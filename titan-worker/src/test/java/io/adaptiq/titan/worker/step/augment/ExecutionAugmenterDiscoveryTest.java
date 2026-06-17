package io.adaptiq.titan.worker.step.augment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.worker.step.ExecutionAugmenter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 42-T — coverage for {@link ExecutionAugmenterDiscovery} (design/42 §4.9). The two built-in
 * augmenters — {@link CredentialsAugmenter} and {@link SshAgentAugmenter} — must be discovered by
 * {@code ServiceLoader} from the worker classpath and ordered by {@link ExecutionAugmenter#order()}
 * (credentials before sshAgent, so the ssh wrapper can rely on the credential env being merged).
 */
class ExecutionAugmenterDiscoveryTest {

  @Test
  void discoversBothBuiltInAugmenters() {
    List<ExecutionAugmenter> augmenters = ExecutionAugmenterDiscovery.discover();
    boolean credentials = augmenters.stream().anyMatch(a -> a instanceof CredentialsAugmenter);
    boolean sshAgent = augmenters.stream().anyMatch(a -> a instanceof SshAgentAugmenter);
    assertTrue(credentials, "CredentialsAugmenter must be discovered via META-INF/services");
    assertTrue(sshAgent, "SshAgentAugmenter must be discovered via META-INF/services");
  }

  @Test
  void credentialsAugmenterIsOrderedBeforeSshAgentAugmenter() {
    List<ExecutionAugmenter> augmenters = ExecutionAugmenterDiscovery.discover();
    int credentialsIdx = indexOf(augmenters, CredentialsAugmenter.class);
    int sshAgentIdx = indexOf(augmenters, SshAgentAugmenter.class);
    assertTrue(credentialsIdx >= 0 && sshAgentIdx >= 0, "both augmenters must be present");
    assertTrue(
        credentialsIdx < sshAgentIdx,
        "credentials must run before sshAgent — the ssh wrapper relies on credential env");
  }

  @Test
  void augmentersAreSortedByOrderAscending() {
    List<ExecutionAugmenter> augmenters = ExecutionAugmenterDiscovery.discover();
    for (int i = 1; i < augmenters.size(); i++) {
      assertTrue(
          augmenters.get(i - 1).order() <= augmenters.get(i).order(),
          "the discovered list must be sorted by order()");
    }
  }

  @Test
  void theBuiltInOrderConstantsKeepCredentialsFirst() {
    // The ordering contract is also pinned on the ORDER constants themselves.
    assertTrue(
        CredentialsAugmenter.ORDER < SshAgentAugmenter.ORDER,
        "CredentialsAugmenter.ORDER must be lower than SshAgentAugmenter.ORDER");
  }

  @Test
  void discoverReturnsANonNullListEveryCall() {
    // Discovery is repeatable and never returns null — TaskExecutor may rebuild it.
    assertSame(
        List.class.isInstance(ExecutionAugmenterDiscovery.discover()) ? Boolean.TRUE : null,
        Boolean.TRUE);
    assertEquals(
        ExecutionAugmenterDiscovery.discover().size(),
        ExecutionAugmenterDiscovery.discover().size(),
        "discovery must be stable across calls");
  }

  private static int indexOf(List<ExecutionAugmenter> list, Class<?> type) {
    for (int i = 0; i < list.size(); i++) {
      if (type.isInstance(list.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
