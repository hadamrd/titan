package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiq.titan.scm.reconcile.EventDedupeStore;
import io.adaptiq.titan.scm.reconcile.ScmProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PulsarScannerScheduler} — the poll driver's sink delivery + typed-error
 * isolation (issue #1280). Drives a real {@link PulsarRepoScanner} over a WireMock-faked node.
 */
class PulsarScannerSchedulerTest {

  private WireMockServer wiremock;
  private PulsarScannerScheduler scheduler;
  private List<PulsarChangeDiscovery> delivered;

  @BeforeEach
  void setUp() {
    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    PulsarClient client = new PulsarClient("http://localhost:" + wiremock.port());
    PulsarRepoScanner scanner = new PulsarRepoScanner(client, new FakeDedupeStore());
    delivered = new ArrayList<>();
    scheduler = new PulsarScannerScheduler(scanner, delivered::add);
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) {
      wiremock.stop();
    }
  }

  @Test
  void tick_deliversEachFreshDiscoveryToTheSink() {
    wiremock.stubFor(
        get(urlEqualTo("/_pulsar/repos")).willReturn(json("{\"repos\":[{\"name\":\"r\"}]}")));
    wiremock.stubFor(
        get(urlEqualTo("/_pulsar/ledger/r/changes"))
            .willReturn(
                json("[{\"id\":\"c1\",\"status\":\"open\",\"revision\":{\"tip\":\"o1\"}}]")));

    int count = scheduler.tick();

    assertEquals(1, count);
    assertEquals(1, delivered.size());
    assertEquals("c1", delivered.get(0).changeId());
  }

  @Test
  void tick_swallowsNodeOutage_returnsZero_doesNotCrashTheDriver() {
    // Repo listing 5xx → scanner raises ScmReconcileException → the driver must survive.
    wiremock.stubFor(get(urlEqualTo("/_pulsar/repos")).willReturn(aResponse().withStatus(502)));

    int count = scheduler.tick();

    assertEquals(0, count);
    assertEquals(0, delivered.size());
  }

  private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(
      String body) {
    return aResponse()
        .withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  static final class FakeDedupeStore implements EventDedupeStore {
    private final Map<String, Instant> seen = new HashMap<>();

    @Override
    public boolean markSeen(ScmProvider provider, String eventId, Source source) {
      return seen.putIfAbsent(provider.wire() + "#" + eventId, Instant.EPOCH) == null;
    }

    @Override
    public void release(ScmProvider provider, String eventId) {
      seen.remove(provider.wire() + "#" + eventId);
    }

    @Override
    public Optional<Instant> firstSeenAt(ScmProvider provider, String eventId) {
      return Optional.ofNullable(seen.get(provider.wire() + "#" + eventId));
    }
  }
}
