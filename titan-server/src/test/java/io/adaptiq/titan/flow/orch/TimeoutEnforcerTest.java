package io.adaptiq.titan.flow.orch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.TimerRow;
import io.adaptiq.titan.timer.TimerService;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TimeoutEnforcer} — exercises {@code armTimeout} against a fake stores stack and
 * confirms the no-op path for a step without a declared timeout. The enforcement path is covered
 * end-to-end by {@code TitanOrchestratorTimeoutIT} (regression oracle).
 */
class TimeoutEnforcerTest {

  @Test
  void armTimeoutInsertsATimerRowForAStepWithATimeout() {
    TitanStores stores = FakeTitanStores.create();
    TimeoutEnforcer enforcer = new TimeoutEnforcer(stores, 42L, new TimerService(stores.timers()));

    StepModel step = new StepModel();
    step.setId("step-1");
    step.setDescriptorId("sh");
    step.setTimeoutMillis(60_000L);

    enforcer.armTimeout(step);

    List<TimerRow> timers = stores.timers().listByBuild(42L);
    assertEquals(1, timers.size(), "armTimeout inserts exactly one timer");
    TimerRow t = timers.get(0);
    assertEquals("TIMEOUT", t.kind);
    assertEquals("step-1", t.nodeId);
    assertNotNull(t.fireAt, "timer must carry a fire-at instant");
  }

  @Test
  void armTimeoutIsANoOpWhenStepHasNoDeclaredTimeout() {
    TitanStores stores = FakeTitanStores.create();
    TimeoutEnforcer enforcer = new TimeoutEnforcer(stores, 7L, new TimerService(stores.timers()));

    StepModel step = new StepModel();
    step.setId("step-1");
    step.setDescriptorId("sh");
    assertNull(step.getTimeoutMillis(), "fixture: step has no timeout");

    enforcer.armTimeout(step);

    assertEquals(0, stores.timers().listByBuild(7L).size(), "no timer when step has no timeout");
  }
}
