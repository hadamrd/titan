package io.adaptiq.titan.scm.github;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.Test;

/**
 * Issue #873: verifies the manifest-callback DTO carries the full Probot shape and the shared
 * {@link GithubJson} mapper silently tolerates fields GitHub adds in the future. A regression here
 * would mean the live manifest exchange 500s the next time GitHub bolts a new property onto the
 * response payload.
 */
class GithubManifestCallbackTest {

  @Test
  void mapper_isTolerantOfUnknownProperties() {
    assertTrue(
        GithubJson.MAPPER.isEnabled(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES),
        "FAIL_ON_NULL_FOR_PRIMITIVES must stay ON to surface missing required fields");
    assertTrue(
        !GithubJson.MAPPER.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES),
        "FAIL_ON_UNKNOWN_PROPERTIES must be OFF for GitHub forward-compat (#873)");
  }

  @Test
  void deserialize_fullProbotShape_populatesEveryField() throws Exception {
    String json =
        "{"
            + "\"id\":12345,"
            + "\"slug\":\"titan-ci\","
            + "\"name\":\"Titan CI\","
            + "\"html_url\":\"https://github.com/apps/titan-ci\","
            + "\"pem\":\"-----BEGIN RSA PRIVATE KEY-----\\nMIIEpA...\\n-----END RSA PRIVATE KEY-----\","
            + "\"webhook_secret\":\"super-secret\","
            + "\"client_id\":\"Iv1.abc123\","
            + "\"client_secret\":\"client-secret-xyz\","
            + "\"node_id\":\"A_kwAB\","
            + "\"owner\":{\"login\":\"acme\",\"id\":42,\"type\":\"Organization\"},"
            + "\"permissions\":{\"contents\":\"read\",\"checks\":\"write\"},"
            + "\"events\":[\"push\",\"pull_request\"],"
            + "\"created_at\":\"2026-05-27T10:00:00Z\","
            + "\"updated_at\":\"2026-05-27T10:00:00Z\","
            + "\"description\":\"CI agent for Titan\""
            + "}";

    GithubManifestCallback m = GithubJson.MAPPER.readValue(json, GithubManifestCallback.class);

    assertEquals(12345L, m.id);
    assertEquals("titan-ci", m.slug);
    assertEquals("Titan CI", m.name);
    assertEquals("https://github.com/apps/titan-ci", m.htmlUrl);
    assertTrue(m.pem != null && m.pem.contains("BEGIN RSA PRIVATE KEY"));
    assertEquals("super-secret", m.webhookSecret);
    assertEquals("Iv1.abc123", m.clientId);
    assertEquals("client-secret-xyz", m.clientSecret);
    assertEquals("A_kwAB", m.nodeId);
    assertNotNull(m.owner);
    assertEquals("acme", m.owner.get("login"));
    assertNotNull(m.permissions);
    assertEquals("read", m.permissions.get("contents"));
    assertEquals("write", m.permissions.get("checks"));
    assertNotNull(m.events);
    assertTrue(m.events.contains("push"));
    assertTrue(m.events.contains("pull_request"));
    assertEquals("2026-05-27T10:00:00Z", m.createdAt);
    assertEquals("CI agent for Titan", m.description);
  }

  @Test
  void deserialize_tolerantToUnknownFutureFields() {
    // Three fabricated fields GitHub might add tomorrow. Plus a nested unknown sub-object on
    // `owner` to exercise the property-by-property tolerance, not just top-level.
    String json =
        "{"
            + "\"id\":1,"
            + "\"slug\":\"x\","
            + "\"pem\":\"p\","
            + "\"webhook_secret\":\"w\","
            + "\"client_id\":\"cid\","
            + "\"fabricated_field_one\":\"value\","
            + "\"fabricated_field_two\":123,"
            + "\"fabricated_field_three\":{\"nested\":true},"
            + "\"owner\":{\"login\":\"o\",\"future_field\":\"x\"}"
            + "}";

    GithubManifestCallback m =
        assertDoesNotThrow(
            () -> GithubJson.MAPPER.readValue(json, GithubManifestCallback.class),
            "unknown fields must not throw — that's the whole point of #873");
    assertEquals(1L, m.id);
    assertEquals("cid", m.clientId);
    assertEquals("o", m.owner.get("login"));
  }

  @Test
  void deserialize_demoNightFailingPayload_populatesNewFields() throws Exception {
    // The exact shape that blew up the live demo per issue #873.
    String json =
        "{"
            + "\"id\":900000,"
            + "\"client_id\":\"Iv1.demo-night-client\","
            + "\"client_secret\":\"demo-night-secret\","
            + "\"owner\":{\"login\":\"demo-org\",\"type\":\"Organization\"},"
            + "\"name\":\"Titan Demo\","
            + "\"description\":\"Demo App\","
            + "\"external_url\":\"https://titan.test.example.com\","
            + "\"html_url\":\"https://github.com/apps/titan-demo\","
            + "\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\",\"checks\":\"write\"},"
            + "\"events\":[\"push\",\"pull_request\",\"check_suite\"],"
            + "\"pem\":\"-----BEGIN RSA PRIVATE KEY-----\\nabc\\n-----END RSA PRIVATE KEY-----\","
            + "\"webhook_secret\":\"the-secret\","
            + "\"slug\":\"titan-demo\""
            + "}";

    GithubManifestCallback m = GithubJson.MAPPER.readValue(json, GithubManifestCallback.class);

    assertEquals(900000L, m.id);
    assertEquals("Iv1.demo-night-client", m.clientId);
    assertEquals("demo-night-secret", m.clientSecret);
    assertEquals("demo-org", m.owner.get("login"));
    assertEquals("read", m.permissions.get("contents"));
    assertEquals("write", m.permissions.get("checks"));
    assertTrue(m.events.contains("check_suite"));
    assertEquals("https://titan.test.example.com", m.externalUrl);
  }
}
