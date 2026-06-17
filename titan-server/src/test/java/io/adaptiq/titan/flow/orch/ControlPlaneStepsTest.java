package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pin the closed membership of {@link ControlPlaneSteps#IDS} (GH #805). Adding a new
 * controller-native descriptor MUST be an explicit edit here AND a matching handler in {@code
 * TitanOrchestrator#advanceSteps} — this test makes the registry change visible in code review.
 */
class ControlPlaneStepsTest {

  @Test
  void idsContainsExactlyTheKnownControllerNativeDescriptors() {
    assertEquals(
        Set.of("sleep", "waitUntil", "approval", "setBuildName"),
        ControlPlaneSteps.IDS,
        "registry membership change — confirm a TitanOrchestrator handler exists for each id");
  }

  @Test
  void isControlPlaneRecognisesEveryRegisteredId() {
    for (String id : ControlPlaneSteps.IDS) {
      assertTrue(ControlPlaneSteps.isControlPlane(id), id + " must be flagged control-plane");
    }
  }

  @Test
  void isControlPlaneRejectsRegularWorkerSteps() {
    assertFalse(ControlPlaneSteps.isControlPlane("sh"));
    assertFalse(ControlPlaneSteps.isControlPlane("echo"));
    assertFalse(ControlPlaneSteps.isControlPlane("archiveArtifacts"));
    assertFalse(ControlPlaneSteps.isControlPlane(""));
  }

  @Test
  void isControlPlaneIsNullSafe() {
    assertFalse(ControlPlaneSteps.isControlPlane(null));
  }
}
