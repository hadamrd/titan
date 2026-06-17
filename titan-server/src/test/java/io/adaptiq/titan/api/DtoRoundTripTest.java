package io.adaptiq.titan.api;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.adaptiq.titan.api.dto.BuildDto;
import io.adaptiq.titan.api.dto.FlowNodeDto;
import io.adaptiq.titan.api.dto.JobDto;
import io.adaptiq.titan.api.dto.JobsPage;
import io.adaptiq.titan.api.dto.ProblemJson;
import io.adaptiq.titan.api.dto.TriggerBuildRequest;
import io.adaptiq.titan.api.dto.TriggerBuildResponse;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves every API DTO survives a Jackson round-trip (serialize → deserialize → re-serialize). No
 * HTTP server, no DB — pure Jackson behaviour.
 */
class DtoRoundTripTest {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

  @Test
  void jobDto_roundTrip() throws Exception {
    JobRow row = new JobRow();
    row.id = 1L;
    row.fullName = "org/repo";
    row.displayName = "My Repo";
    row.folderPath = "org";
    row.enabled = true;
    row.createdAt = Instant.parse("2026-01-01T00:00:00Z");
    row.updatedAt = Instant.parse("2026-01-02T00:00:00Z");
    row.pipelineScript = "stages:\n  - name: build\n    steps: []\n";
    row.configJson = "{}";

    JobDto original = JobDto.from(row);
    String json = MAPPER.writeValueAsString(original);
    JobDto restored = MAPPER.readValue(json, JobDto.class);

    assertEquals(original.id(), restored.id());
    assertEquals(original.fullName(), restored.fullName());
    assertEquals(original.displayName(), restored.displayName());
    assertEquals(original.folderPath(), restored.folderPath());
    assertEquals(original.enabled(), restored.enabled());
    assertEquals(original.createdAt(), restored.createdAt());
    assertEquals("stages:\n  - name: build\n    steps: []\n", restored.pipelineScript());
  }

  @Test
  void jobDto_nullDisplayNameFallsBackToFullName() {
    JobRow row = new JobRow();
    row.id = 2L;
    row.fullName = "org/no-display";
    row.displayName = null;
    row.enabled = true;
    row.createdAt = Instant.now();
    row.updatedAt = Instant.now();
    row.pipelineScript = "";
    row.configJson = "{}";

    JobDto dto = JobDto.from(row);
    assertEquals(
        "org/no-display", dto.displayName(), "null displayName must fall back to fullName");
  }

  @Test
  void jobsPage_roundTrip() throws Exception {
    JobRow row = new JobRow();
    row.id = 3L;
    row.fullName = "f";
    row.enabled = false;
    row.createdAt = Instant.now();
    row.updatedAt = Instant.now();
    row.pipelineScript = "";
    row.configJson = "{}";

    JobsPage original = new JobsPage(List.of(JobDto.from(row)), 1, 0, 10);
    String json = MAPPER.writeValueAsString(original);
    JobsPage restored = MAPPER.readValue(json, JobsPage.class);

    assertEquals(1, restored.total());
    assertEquals(0, restored.offset());
    assertEquals(10, restored.limit());
    assertEquals(1, restored.items().size());
    assertEquals(3L, restored.items().get(0).id());
  }

  @Test
  void buildDto_roundTrip() throws Exception {
    BuildRow row = new BuildRow();
    row.id = 42L;
    row.jobId = 7L;
    row.buildNumber = 3;
    row.status = "RUNNING";
    row.triggeredBy = "alice";
    row.triggerType = "manual";
    row.queuedAt = Instant.parse("2026-01-01T10:00:00Z");
    row.startedAt = Instant.parse("2026-01-01T10:00:01Z");
    row.durationMs = null;
    row.errorMessage = null;

    BuildDto original = BuildDto.from(row);
    String json = MAPPER.writeValueAsString(original);
    BuildDto restored = MAPPER.readValue(json, BuildDto.class);

    assertEquals(original.id(), restored.id());
    assertEquals(original.jobId(), restored.jobId());
    assertEquals(original.buildNumber(), restored.buildNumber());
    assertEquals(original.status(), restored.status());
    assertEquals(original.triggeredBy(), restored.triggeredBy());
    assertEquals(original.queuedAt(), restored.queuedAt());
    assertNull(restored.durationMs());
  }

  @Test
  void flowNodeDto_roundTrip() throws Exception {
    FlowNodeRow row = new FlowNodeRow();
    row.buildId = 10L;
    row.nodeId = "n1";
    row.nodeType = "STAGE";
    row.status = "RUNNING";
    row.displayName = "Build";
    row.agentLabel = "linux";
    row.attempt = 2;
    row.maxAttempts = 3;
    row.logTaskId = UUID.randomUUID();

    FlowNodeDto original = FlowNodeDto.from(row);
    String json = MAPPER.writeValueAsString(original);
    FlowNodeDto restored = MAPPER.readValue(json, FlowNodeDto.class);

    assertEquals(original.buildId(), restored.buildId());
    assertEquals(original.nodeId(), restored.nodeId());
    assertEquals(original.nodeType(), restored.nodeType());
    assertEquals(original.status(), restored.status());
    assertEquals(original.attempt(), restored.attempt());
    assertEquals(original.maxAttempts(), restored.maxAttempts());
    assertEquals(original.logTaskId(), restored.logTaskId());
    // outputs default to empty map when result_json is null (no published outputs).
    assertNotNull(restored.outputs());
    assertTrue(restored.outputs().isEmpty());
  }

  /**
   * Issue #782 — outputs surfaced from {@code result_json.outputs} are flattened to a {@code
   * Map<String,String>} on the wire so the UI's key/value table never has to type-switch. Numbers,
   * booleans and nested JSON are stringified via {@code String.valueOf} so a step that publishes
   * {@code setOutput("pushed", true)} round-trips as {@code "true"} (not {@code true}).
   */
  @Test
  void flowNodeDto_outputsFlattenedFromResultJson() throws Exception {
    FlowNodeRow row = new FlowNodeRow();
    row.buildId = 11L;
    row.nodeId = "step.docker.push";
    row.nodeType = "STEP";
    row.status = "SUCCESS";
    row.resultJson =
        "{\"exitCode\":0,\"outputs\":{"
            + "\"image\":\"ghcr.io/acme/api:v1\","
            + "\"digest\":\"sha256:deadbeef\","
            + "\"pushed\":true,"
            + "\"layers\":12"
            + "}}";

    FlowNodeDto dto = FlowNodeDto.from(row);
    assertEquals("ghcr.io/acme/api:v1", dto.outputs().get("image"));
    assertEquals("sha256:deadbeef", dto.outputs().get("digest"));
    assertEquals("true", dto.outputs().get("pushed"));
    assertEquals("12", dto.outputs().get("layers"));
    assertEquals(4, dto.outputs().size());

    // Round-trip preserves all entries as strings.
    String json = MAPPER.writeValueAsString(dto);
    FlowNodeDto restored = MAPPER.readValue(json, FlowNodeDto.class);
    assertEquals(dto.outputs(), restored.outputs());
  }

  /**
   * Malformed / outputs-less {@code result_json} must degrade to an empty map — a step that
   * publishes nothing (or a step whose handler crashed before writing result_json) must not surface
   * as {@code null outputs} on the wire.
   */
  @Test
  void flowNodeDto_outputsEmptyOnMissingOrMalformedResultJson() {
    FlowNodeRow plain = new FlowNodeRow();
    plain.buildId = 1L;
    plain.nodeId = "n1";
    plain.nodeType = "STEP";
    plain.status = "SUCCESS";
    plain.resultJson = "{\"exitCode\":0}"; // no outputs key
    assertTrue(FlowNodeDto.from(plain).outputs().isEmpty());

    FlowNodeRow blank = new FlowNodeRow();
    blank.buildId = 1L;
    blank.nodeId = "n2";
    blank.nodeType = "STEP";
    blank.status = "FAILED";
    blank.resultJson = "";
    assertTrue(FlowNodeDto.from(blank).outputs().isEmpty());

    FlowNodeRow garbage = new FlowNodeRow();
    garbage.buildId = 1L;
    garbage.nodeId = "n3";
    garbage.nodeType = "STEP";
    garbage.status = "FAILED";
    garbage.resultJson = "not json at all";
    assertTrue(FlowNodeDto.from(garbage).outputs().isEmpty());
  }

  @Test
  void triggerBuildRequest_roundTrip() throws Exception {
    TriggerBuildRequest original = new TriggerBuildRequest("{\"branch\":\"main\"}", "alice");
    String json = MAPPER.writeValueAsString(original);
    TriggerBuildRequest restored = MAPPER.readValue(json, TriggerBuildRequest.class);
    assertEquals(original.parametersJson(), restored.parametersJson());
    assertEquals(original.triggeredBy(), restored.triggeredBy());
  }

  @Test
  void triggerBuildRequest_nullFieldsOmittedFromJson() throws Exception {
    TriggerBuildRequest req = new TriggerBuildRequest(null, null);
    String json = MAPPER.writeValueAsString(req);
    // NON_NULL on the record means null fields are omitted
    assertFalse(json.contains("parametersJson"), "null fields should be omitted; json=" + json);
    assertFalse(json.contains("triggeredBy"), "null fields should be omitted; json=" + json);
  }

  @Test
  void triggerBuildResponse_roundTrip() throws Exception {
    TriggerBuildResponse original = new TriggerBuildResponse(99L, 5, "QUEUED");
    String json = MAPPER.writeValueAsString(original);
    TriggerBuildResponse restored = MAPPER.readValue(json, TriggerBuildResponse.class);
    assertEquals(99L, restored.buildId());
    assertEquals(5, restored.buildNumber());
    assertEquals("QUEUED", restored.status());
  }

  @Test
  void problemJson_roundTrip() throws Exception {
    ProblemJson original = ProblemJson.notFound("job 42 not found");
    String json = MAPPER.writeValueAsString(original);
    ProblemJson restored = MAPPER.readValue(json, ProblemJson.class);
    assertEquals(404, restored.status());
    assertEquals("about:blank", restored.type());
    assertEquals("Not Found", restored.title());
    assertEquals("job 42 not found", restored.detail());
    assertNull(restored.instance(), "instance should be null and omitted");
  }

  @Test
  void problemJson_factoryMethods() {
    assertEquals(400, ProblemJson.badRequest("x").status());
    assertEquals(500, ProblemJson.internalError("x").status());
    assertEquals(404, ProblemJson.notFound("x").status());

    ProblemJson full =
        new ProblemJson(
            "https://example.com/errors/not-found", "Not Found", 404, "detail", "/req/1");
    assertEquals("https://example.com/errors/not-found", full.type());
    assertEquals("/req/1", full.instance());
  }
}
