package io.adaptiq.titan.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class ChaosWorkerTest {

  @Test
  void runsZeroExitCommand() {
    String payload = ChaosWorker.payloadFor("true");
    assertEquals(0, ChaosWorker.runRealSubprocess(payload));
  }

  @Test
  void runsNonZeroExitCommand() {
    String payload = ChaosWorker.payloadFor("exit 1");
    assertNotEquals(0, ChaosWorker.runRealSubprocess(payload));
  }

  @Test
  void runsEchoCommand() {
    String payload = ChaosWorker.payloadFor("echo hello");
    assertEquals(0, ChaosWorker.runRealSubprocess(payload));
  }
}
