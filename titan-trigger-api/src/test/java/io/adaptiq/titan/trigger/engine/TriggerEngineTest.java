package io.adaptiq.titan.trigger.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.adaptiq.titan.trigger.CronTrigger;
import io.adaptiq.titan.trigger.Trigger;
import io.adaptiq.titan.trigger.TriggerCodec;
import io.adaptiq.titan.trigger.TriggerDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Wiring coverage for the trigger module — proves the pure unit tests' assumptions about discovery
 * hold in the assembled jar: the ServiceLoader descriptor registry sees {@code CronTrigger}, and
 * {@link TriggerCodec} round-trips through it (the mechanism that makes a third-party trigger type
 * Just Work).
 *
 * <p>The Quarkus engine wiring (the {@code @Scheduled} bean + CDI {@code Instance<>} collection) is
 * covered by {@code DiscoveryScheduler}-style integration tests in a follow-up wave; this Wave-1
 * test pins descriptor registry + codec round-trip only.
 */
class TriggerEngineTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void cronTriggerTypeIsRegisteredInTheDescriptorRegistry() {
    boolean found = TriggerDescriptor.all().stream().anyMatch(d -> "cron".equals(d.triggerType()));
    assertTrue(
        found,
        "CronTrigger.DescriptorImpl must be registered in"
            + " META-INF/services/io.adaptiq.titan.trigger.TriggerDescriptor");
  }

  @Test
  void codecRoundTripsThroughTheLiveDescriptorRegistry() throws Exception {
    ArrayNode json = TriggerCodec.write(List.of(new CronTrigger("t-1", "H 3 * * *")), MAPPER);
    List<Trigger> back = TriggerCodec.read(MAPPER.readTree(json.toString()));

    assertEquals(1, back.size());
    CronTrigger cron = assertInstanceOf(CronTrigger.class, back.get(0));
    assertEquals("t-1", cron.getId());
    assertEquals("H 3 * * *", cron.getSpec());
  }
}
