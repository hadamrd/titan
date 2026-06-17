package io.adaptiq.titan.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiq.titan.api.dto.PulsarSourceDto;
import io.adaptiq.titan.api.dto.RegisterPulsarSourceRequest;
import io.adaptiq.titan.scm.pulsar.PulsarClient;
import io.adaptiq.titan.store.TitanStores;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PulsarSourcesApi} (#1283) exercising the REAL code paths: a fresh in-memory
 * {@code FakeTitanStores} (real {@link PulsarSourceDao}) plus a per-node resolver that hands back a
 * REAL {@link PulsarClient} pointed at a class-scoped WireMock node. The reachability probe
 * therefore drives the genuine {@code PulsarClient.listRepos()} HTTP transport — no DAO mock, no
 * client mock.
 *
 * <p>Adversarial coverage: malformed URL (400), duplicate (409), unreachable node (502 + NOT
 * persisted), happy-path repoCount projection, sync refresh, sync of an unknown id (404).
 */
class PulsarSourcesApiTest {

  private WireMockServer wiremock;
  private TitanStores stores;
  private PulsarSourcesApi api;
  private String nodeUrl;

  @BeforeEach
  void setUp() {
    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    nodeUrl = "http://localhost:" + wiremock.port();
    stores = FakeTitanStores.create();
    // Resolver returns a REAL PulsarClient for the requested node — listRepos() does real HTTP.
    Function<String, PulsarClient> resolver = PulsarClient::new;
    api = new PulsarSourcesApi(stores, resolver);
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) {
      wiremock.stop();
    }
  }

  // ── GET ─────────────────────────────────────────────────────────────────────

  @Test
  void list_emptyByDefault() {
    assertTrue(api.list().isEmpty());
  }

  @Test
  void list_returnsPersistedSources() {
    stubRepos("[{\"name\":\"r1\"},{\"name\":\"r2\"}]");
    register(nodeUrl, "prod");

    List<PulsarSourceDto> all = api.list();
    assertEquals(1, all.size());
    assertEquals(nodeUrl, all.get(0).nodeUrl());
    assertEquals("prod", all.get(0).nodeName());
    assertEquals(Integer.valueOf(2), all.get(0).repoCount());
  }

  // ── POST register ─────────────────────────────────────────────────────────

  @Test
  void register_happyPath_persistsAndProjectsRepoCount() {
    stubRepos("[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]");

    Response resp = api.register(new RegisterPulsarSourceRequest(nodeUrl, "my-node"));
    assertEquals(201, resp.getStatus());
    PulsarSourceDto dto = (PulsarSourceDto) resp.getEntity();
    assertNotNull(dto);
    assertEquals(nodeUrl, dto.nodeUrl());
    assertEquals("my-node", dto.nodeName());
    assertEquals(Integer.valueOf(3), dto.repoCount());
    assertNotNull(dto.lastPolledAt());
    assertNotNull(dto.createdAt());

    // Actually persisted.
    assertTrue(stores.pulsarSources().findByNodeUrl(nodeUrl).isPresent());
  }

  @Test
  void register_blankUrl_throws400() {
    assertThrows(
        ApiBadRequestException.class,
        () -> api.register(new RegisterPulsarSourceRequest("   ", null)));
  }

  @Test
  void register_malformedUrl_throws400() {
    assertThrows(
        ApiBadRequestException.class,
        () -> api.register(new RegisterPulsarSourceRequest("not-a-url", null)));
    assertThrows(
        ApiBadRequestException.class,
        () -> api.register(new RegisterPulsarSourceRequest("ftp://host/x", null)));
    // Nothing persisted for any rejected URL.
    assertTrue(stores.pulsarSources().listAll().isEmpty());
  }

  @Test
  void register_duplicate_returns409_andDoesNotDoubleInsert() {
    stubRepos("[{\"name\":\"r1\"}]");
    assertEquals(201, register(nodeUrl, "first").getStatus());

    Response dup = api.register(new RegisterPulsarSourceRequest(nodeUrl, "second"));
    assertEquals(409, dup.getStatus());
    assertEquals(1, stores.pulsarSources().listAll().size());
  }

  @Test
  void register_unreachableNode_returns502_andDoesNotPersist() {
    // Node answers 503 → PulsarClient throws PulsarApiException → typed 502.
    wiremock.stubFor(get(urlEqualTo("/_pulsar/repos")).willReturn(aResponse().withStatus(503)));

    Response resp = api.register(new RegisterPulsarSourceRequest(nodeUrl, "down"));
    assertEquals(502, resp.getStatus());
    // Crucially: an unreachable node is NEVER persisted.
    assertTrue(stores.pulsarSources().listAll().isEmpty());
  }

  // ── POST {id}/sync ──────────────────────────────────────────────────────────

  @Test
  void sync_refreshesRepoCount() {
    stubRepos("[{\"name\":\"r1\"}]");
    PulsarSourceDto registered = (PulsarSourceDto) register(nodeUrl, "n").getEntity();
    assertEquals(Integer.valueOf(1), registered.repoCount());

    // Node now reports 4 repos — sync must re-probe and refresh.
    stubRepos("[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"},{\"name\":\"d\"}]");
    Response resp = api.sync(String.valueOf(registered.id()));
    assertEquals(200, resp.getStatus());
    PulsarSourceDto refreshed = (PulsarSourceDto) resp.getEntity();
    assertEquals(Integer.valueOf(4), refreshed.repoCount());
    assertNotNull(refreshed.lastPolledAt());
  }

  @Test
  void sync_unknownId_throws404() {
    assertThrows(ApiNotFoundException.class, () -> api.sync("987654"));
  }

  @Test
  void sync_unreachableNode_returns502() {
    stubRepos("[{\"name\":\"r1\"}]");
    PulsarSourceDto registered = (PulsarSourceDto) register(nodeUrl, "n").getEntity();

    wiremock.stubFor(get(urlEqualTo("/_pulsar/repos")).willReturn(aResponse().withStatus(500)));
    Response resp = api.sync(String.valueOf(registered.id()));
    assertEquals(502, resp.getStatus());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Response register(String url, String name) {
    return api.register(new RegisterPulsarSourceRequest(url, name));
  }

  private void stubRepos(String reposArrayJson) {
    wiremock.stubFor(
        get(urlEqualTo("/_pulsar/repos"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"repos\":" + reposArrayJson + "}")));
  }
}
