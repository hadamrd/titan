package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.adaptiq.titan.api.FakeTitanStores;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import io.adaptiq.titan.store.TitanStores;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the queued→in_progress→final check lifecycle (issue #5), sitting alongside
 * {@link PulsarCloneDiscoveryIT}. It crosses the full module boundary: a real {@link
 * PulsarCheckReporter} → real {@link PulsarClient} → a WireMock node that behaves like the live
 * Pulsar {@code append_event}/{@code EventKind::CiStatus} handler — it RECORDS each posted CI event
 * into an in-memory ledger and folds {@code conclusion} into a merge-gate verdict exactly as the
 * node does ({@code success → allowed}, anything else → refused).
 *
 * <p>This is the integration-level proof that the chosen {@code phase} extra-field mechanism is
 * node-accepted (the stub deserializes the event tolerantly, keeping the sibling field) and that
 * the gate flips ONLY on the terminal success event — the two acceptance guarantees that the unit
 * test's pure wire assertions cannot demonstrate end-to-end.
 */
class PulsarCheckLifecycleIT {

  private static final String REPO = "sample";
  private static final String CHANGE_ID = "42";
  private static final String EVENTS_URL =
      "/_pulsar/ledger/" + REPO + "/changes/" + CHANGE_ID + "/events";

  private WireMockServer node;
  private TitanStores stores;
  private PulsarCheckReporter reporter;
  private long jobId;

  @BeforeEach
  void setUp() {
    node = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    node.start();
    // Stand in for the node's append_event handler: accept the event (201). It is recorded in
    // WireMock's request journal, which is populated synchronously as the request is served — so
    // the ledger/gate are read from the journal AFTER each blocking report() call rather than from
    // an addMockServiceRequestListener callback (that notification can fire after the client has
    // already received its 201, racing a bare volatile read — sev3 review). Tolerant by design: an
    // extra "phase" sibling field is kept, not rejected; the gate keys off conclusion alone.
    node.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    stores = FakeTitanStores.create();
    seedJob(REPO);
    PulsarClient client = new PulsarClient("http://localhost:" + node.port());
    reporter = new PulsarCheckReporter(stores, client, "https://titan.example.com", true);
  }

  @AfterEach
  void tearDown() {
    if (node != null) {
      node.stop();
    }
  }

  @Test
  void queuedRunningSuccess_recordsThreeDistinctLifecycleEvents_gateFlipsOnlyOnSuccess() {
    reporter.report(event("QUEUED"));
    // Gate must stay refused while enqueued.
    assertEquals("refused_incomplete", gate(), "queued must not clear the gate");

    reporter.report(event("RUNNING"));
    // Gate must stay refused while running.
    assertEquals("refused_incomplete", gate(), "in_progress must not clear the gate");

    reporter.report(event("SUCCESS"));
    assertEquals("allowed", gate(), "gate clears ONLY on the terminal success event");

    // Three ordered, DISTINCT events landed on the change ledger.
    List<JsonNode> ledger = ledger();
    assertEquals(3, ledger.size(), "expected three lifecycle events on the ledger");

    JsonNode queued = ledger.get(0);
    assertEquals("ci", queued.path("kind").asText());
    assertEquals("pending", queued.path("conclusion").asText());
    assertEquals("queued", queued.path("phase").asText());

    JsonNode running = ledger.get(1);
    assertEquals("pending", running.path("conclusion").asText());
    assertEquals("in_progress", running.path("phase").asText());

    JsonNode success = ledger.get(2);
    assertEquals("success", success.path("conclusion").asText());
    assertFalse(success.has("phase"), "terminal success carries no in-flight phase");

    // The two pendings are NOT byte-identical — the GitHub-parity regression this issue fixes.
    assertFalse(
        queued.path("phase").asText().equals(running.path("phase").asText()),
        "queued and in_progress events must be distinguishable");
    assertTrue(ledger.get(0).has("phase") && ledger.get(1).has("phase"));
  }

  /**
   * The change ledger, folded from WireMock's request journal in posted order. The journal is
   * populated synchronously while each request is served, so it is fully visible the moment the
   * blocking {@code report()} (and its HTTP send) returns — no async listener race.
   */
  private List<JsonNode> ledger() {
    List<JsonNode> events = new ArrayList<>();
    for (LoggedRequest req : node.findAll(postRequestedFor(urlEqualTo(EVENTS_URL)))) {
      try {
        events.add(PulsarJson.MAPPER.readTree(req.getBody()));
      } catch (Exception e) {
        throw new IllegalStateException("stub node failed to parse CI event", e);
      }
    }
    return events;
  }

  /** The merge-gate verdict the node folds from the ledger: cleared ONLY by a terminal success. */
  private String gate() {
    for (JsonNode event : ledger()) {
      if ("success".equals(event.path("conclusion").asText())) {
        return "allowed";
      }
    }
    return "refused_incomplete";
  }

  private BuildStateChangedEvent event(String status) {
    return new BuildStateChangedEvent(
        4242L,
        status,
        "pulsar",
        "{\"commitSha\":\"oid1abc\",\"changeId\":\"" + CHANGE_ID + "\"}",
        jobId,
        1);
  }

  private void seedJob(String fullName) {
    stores.withTransaction(
        conn -> {
          try (PreparedStatement ps =
              conn.prepareStatement(
                  "INSERT INTO titan.jobs (full_name, pipeline_script, config_json, enabled) "
                      + "VALUES (?, 'pipeline {}', '{}', TRUE)")) {
            ps.setString(1, fullName);
            ps.executeUpdate();
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
          return null;
        });
    jobId = stores.jobs().findByFullName(fullName).orElseThrow().id;
  }
}
