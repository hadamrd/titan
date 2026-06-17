package io.adaptiq.titan.trigger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.adaptiq.titan.trigger.TriggerCodec.TriggerReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Ported from {@code io.adaptiq.scheduler.TriggerCodecTest} (Wave 1). */
class TriggerCodecTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final Map<String, TriggerReader> READERS =
      Map.of("cron", (id, node) -> new CronTrigger(id, node.path("spec").asText("")));

  @Test
  void roundTripsACronTrigger() throws Exception {
    List<Trigger> original = List.of(new CronTrigger("trig-1", "H 2 * * *"));
    ArrayNode json = TriggerCodec.write(original, MAPPER);

    List<Trigger> back = TriggerCodec.read(MAPPER.readTree(json.toString()), READERS);
    assertEquals(1, back.size());
    CronTrigger cron = assertInstanceOf(CronTrigger.class, back.get(0));
    assertEquals("trig-1", cron.getId());
    assertEquals("H 2 * * *", cron.getSpec());
    assertEquals("cron", cron.getType());
  }

  @Test
  void roundTripsMultipleTriggersPreservingOrder() throws Exception {
    List<Trigger> original =
        List.of(new CronTrigger("a", "@daily"), new CronTrigger("b", "@hourly"));
    List<Trigger> back =
        TriggerCodec.read(
            MAPPER.readTree(TriggerCodec.write(original, MAPPER).toString()), READERS);
    assertEquals(List.of("a", "b"), back.stream().map(Trigger::getId).toList());
  }

  @Test
  void readsNullOrNonArrayAsEmpty() {
    assertTrue(TriggerCodec.read(null, READERS).isEmpty());
    assertTrue(TriggerCodec.read(MAPPER.createObjectNode(), READERS).isEmpty());
  }

  @Test
  void unknownTriggerTypeIsSkippedNotFatal() throws Exception {
    String json =
        "[{\"type\":\"cron\",\"id\":\"keep\",\"spec\":\"@daily\"},"
            + "{\"type\":\"from-the-future\",\"id\":\"drop\"}]";
    List<Trigger> back = TriggerCodec.read(MAPPER.readTree(json), READERS);
    assertEquals(1, back.size(), "an unknown type is skipped, the known one survives");
    assertEquals("keep", back.get(0).getId());
  }

  @Test
  void anEntryWithoutAnIdGetsAMintedOne() throws Exception {
    List<Trigger> back =
        TriggerCodec.read(MAPPER.readTree("[{\"type\":\"cron\",\"spec\":\"@daily\"}]"), READERS);
    assertEquals(1, back.size());
    assertTrue(back.get(0).getId() != null && !back.get(0).getId().isBlank());
  }

  @Test
  void writeAsksEachTriggerForItsOwnState() throws Exception {
    ArrayNode json = TriggerCodec.write(List.of(new CronTrigger("x", "@weekly")), MAPPER);
    assertEquals("cron", json.get(0).get("type").asText());
    assertEquals("x", json.get(0).get("id").asText());
    assertEquals("@weekly", json.get(0).get("spec").asText());
  }
}
