package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link WebhookPayloadParams} — the {@code client_payload} extractor introduced
 * by #938. Pinned cases: happy-path scalar mix, missing / null / non-object payloads, non-scalar
 * value drop, event-type filter, and the JSON-serialisation contract on the {@code parameters_json}
 * column.
 */
class WebhookPayloadParamsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode parse(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ── happy path ────────────────────────────────────────────────────────────

  @Test
  void extract_repositoryDispatch_clientPayloadScalars_allKept() {
    JsonNode payload =
        parse(
            "{\"action\":\"deploy\","
                + "\"client_payload\":{\"TAG\":\"v1.0\",\"COUNT\":3,\"FORCE\":true}}");
    Map<String, String> out = WebhookPayloadParams.extract(payload, "repository_dispatch");
    assertEquals("v1.0", out.get("TAG"));
    assertEquals("3", out.get("COUNT"));
    assertEquals("true", out.get("FORCE"));
    assertEquals(3, out.size());
  }

  @Test
  void extract_eventCaseInsensitive() {
    JsonNode payload = parse("{\"client_payload\":{\"X\":\"y\"}}");
    assertEquals("y", WebhookPayloadParams.extract(payload, "Repository_Dispatch").get("X"));
    assertEquals("y", WebhookPayloadParams.extract(payload, "REPOSITORY_DISPATCH").get("X"));
  }

  @Test
  void suppliedKeys_matchesExtractKeySet() {
    JsonNode payload = parse("{\"client_payload\":{\"A\":\"1\",\"B\":2}}");
    assertEquals(
        Map.of("A", "1", "B", "2").keySet(),
        WebhookPayloadParams.suppliedKeys(payload, "repository_dispatch"));
  }

  // ── sad paths — never throw ───────────────────────────────────────────────

  @Test
  void extract_nullPayload_empty() {
    assertTrue(WebhookPayloadParams.extract(null, "repository_dispatch").isEmpty());
  }

  @Test
  void extract_nullEvent_empty() {
    assertTrue(
        WebhookPayloadParams.extract(parse("{\"client_payload\":{\"X\":\"y\"}}"), null).isEmpty());
  }

  @Test
  void extract_nonRepositoryDispatchEvent_empty() {
    // Push payloads have no inputs — even if the body has a client_payload field, the event
    // filter must drop them. This is the guarantee that protects regression of plain push.
    JsonNode payload = parse("{\"client_payload\":{\"TAG\":\"v1\"}}");
    assertTrue(WebhookPayloadParams.extract(payload, "push").isEmpty());
    assertTrue(WebhookPayloadParams.extract(payload, "pull_request").isEmpty());
    assertTrue(WebhookPayloadParams.extract(payload, "ping").isEmpty());
  }

  @Test
  void extract_missingClientPayload_empty() {
    JsonNode payload = parse("{\"action\":\"deploy\"}");
    assertTrue(WebhookPayloadParams.extract(payload, "repository_dispatch").isEmpty());
  }

  @Test
  void extract_nullClientPayload_empty() {
    JsonNode payload = parse("{\"client_payload\":null}");
    assertTrue(WebhookPayloadParams.extract(payload, "repository_dispatch").isEmpty());
  }

  @Test
  void extract_clientPayloadArray_empty() {
    // Malformed shape: client_payload is an array. Must not 500; just return empty.
    JsonNode payload = parse("{\"client_payload\":[\"TAG\",\"v1\"]}");
    assertTrue(WebhookPayloadParams.extract(payload, "repository_dispatch").isEmpty());
  }

  @Test
  void extract_clientPayloadString_empty() {
    JsonNode payload = parse("{\"client_payload\":\"not-an-object\"}");
    assertTrue(WebhookPayloadParams.extract(payload, "repository_dispatch").isEmpty());
  }

  // ── value-typing rules ────────────────────────────────────────────────────

  @Test
  void extract_nonScalarValuesDropped_scalarsKept() {
    // Nested object / array values must be silently dropped — Titan's param surface is scalar.
    // The sibling scalar in the same payload must survive intact.
    JsonNode payload =
        parse(
            "{\"client_payload\":{\"TAG\":\"v1\",\"NESTED\":{\"a\":1},\"LIST\":[1,2],\"NULL_V\":null}}");
    Map<String, String> out = WebhookPayloadParams.extract(payload, "repository_dispatch");
    assertEquals("v1", out.get("TAG"));
    assertEquals(1, out.size(), "only scalar TAG must survive — nested/array/null are dropped");
  }

  @Test
  void extract_emptyKeyDropped() {
    JsonNode payload = parse("{\"client_payload\":{\"\":\"x\",\"K\":\"v\"}}");
    Map<String, String> out = WebhookPayloadParams.extract(payload, "repository_dispatch");
    assertEquals(Map.of("K", "v"), out);
  }

  @Test
  void extract_emptyObject_empty() {
    JsonNode payload = parse("{\"client_payload\":{}}");
    assertTrue(WebhookPayloadParams.extract(payload, "repository_dispatch").isEmpty());
  }

  // ── parameters_json serialisation ─────────────────────────────────────────

  @Test
  void toParametersJson_emptyMap_null() {
    assertNull(WebhookPayloadParams.toParametersJson(Map.of()));
  }

  @Test
  void toParametersJson_singleScalar_renders() throws Exception {
    String json = WebhookPayloadParams.toParametersJson(Map.of("TAG", "v1.0"));
    // Round-trip through Jackson so the assertion isn't fragile w.r.t. key order or whitespace.
    JsonNode parsed = MAPPER.readTree(json);
    assertEquals("v1.0", parsed.get("TAG").asText());
  }

  @Test
  void toParametersJson_preservesInsertionOrder() throws Exception {
    java.util.LinkedHashMap<String, String> in = new java.util.LinkedHashMap<>();
    in.put("Z", "1");
    in.put("A", "2");
    String json = WebhookPayloadParams.toParametersJson(in);
    // LinkedHashMap → Jackson preserves insertion order.
    assertTrue(json.indexOf("\"Z\"") < json.indexOf("\"A\""), "expected Z before A in: " + json);
  }
}
