package io.adaptiq.titan.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The design/42 regression fix: a {@code TitanLauncher} freestyle task ships a top-level {@code
 * command} argv and no step shape, and must take the raw-command path — NOT the StepHandler path
 * that would fall back to {@code sh} and reject it for a missing {@code script} argument. These
 * tests pin the step-path-vs-raw-path decision rule.
 */
final class TaskExecutorRawCommandTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void titanLauncherFreestyleCommandIsRawPath() {
    // Exactly what TitanLauncher emits for an "Execute shell" freestyle build.
    JsonNode payload =
        parse(
            "{\"command\":[\"/bin/sh\",\"-xe\",\"/titan/tmp/x.sh\"],"
                + "\"env\":{\"BUILD_NUMBER\":\"7\"}}");
    assertTrue(TaskExecutor.isRawCommandPayload(payload));
  }

  @Test
  void rawCommandWithImageIsStillRawPath() {
    JsonNode payload = parse("{\"command\":[\"echo\",\"hi\"],\"image\":\"node:18\"}");
    assertTrue(TaskExecutor.isRawCommandPayload(payload));
  }

  @Test
  void stepShapedPayloadWithDescriptorIsStepPath() {
    JsonNode payload =
        parse("{\"stepDescriptor\":\"sh\"," + "\"arguments\":{\"script\":\"echo hi\"}}");
    assertFalse(TaskExecutor.isRawCommandPayload(payload));
  }

  @Test
  void stepShapedPayloadWithArgumentsOnlyIsStepPath() {
    JsonNode payload = parse("{\"arguments\":{\"script\":\"echo hi\"}}");
    assertFalse(TaskExecutor.isRawCommandPayload(payload));
  }

  @Test
  void scriptStepPayloadIsStepPath() {
    JsonNode payload = parse("{\"runtime\":\"groovy\",\"body\":\"echo 'hi'\"}");
    assertFalse(TaskExecutor.isRawCommandPayload(payload));
  }

  @Test
  void payloadWithoutCommandArrayIsNotRawPath() {
    // No command, no step shape — falls through to the step path's clear error.
    assertFalse(TaskExecutor.isRawCommandPayload(parse("{\"env\":{}}")));
  }

  @Test
  void commandAsScalarIsNotRawPath() {
    // A non-array `command` is not the raw argv shape.
    assertFalse(TaskExecutor.isRawCommandPayload(parse("{\"command\":\"echo hi\"}")));
  }

  @Test
  void ambiguousPayloadWithBothShapesPrefersStepPath() {
    // A defensive tiebreak: if a payload somehow carries both a command array and a step
    // descriptor, the explicit step shape wins — the raw path is only for pure command argv.
    JsonNode payload = parse("{\"command\":[\"echo\",\"hi\"],\"stepDescriptor\":\"sh\"}");
    assertFalse(TaskExecutor.isRawCommandPayload(payload));
  }
}
