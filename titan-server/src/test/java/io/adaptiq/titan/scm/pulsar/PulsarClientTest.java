package io.adaptiq.titan.scm.pulsar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.adaptiq.titan.scm.pulsar.PulsarClient.CheckConclusion;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * WireMock-backed unit tests for {@link PulsarClient#listOpenChanges(String)} (live-rig fix). The
 * Pulsar node returns the changes as a bare JSON ARRAY ({@code [{"id":..,"status":..,
 * "revision":{"tip":..}}, ..]}), not an object envelope; the client must parse that shape and
 * return only {@code status == "open"} changes that carry a non-blank {@code revision.tip}.
 */
class PulsarClientTest {

  private static final String REPO = "sample";
  private static final String CHANGE_ID = "c1";
  private static final String CHANGES_URL = "/_pulsar/ledger/" + REPO + "/changes";
  private static final String REFS_URL = "/_pulsar/repos/" + REPO + "/refs";
  private static final String EVENTS_URL =
      "/_pulsar/ledger/" + REPO + "/changes/" + CHANGE_ID + "/events";

  private WireMockServer wiremock;
  private PulsarClient client;

  @BeforeEach
  void setUp() {
    wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    wiremock.start();
    client = new PulsarClient("http://localhost:" + wiremock.port());
  }

  @AfterEach
  void tearDown() {
    if (wiremock != null) {
      wiremock.stop();
    }
  }

  /**
   * Regression for the live root cause: the {@code /changes} body is a bare ARRAY where each open
   * change carries its own {@code revision.tip}. listOpenChanges must return ONLY changes that are
   * {@code open} AND carry a non-blank tip, in order — exactly the live payload shape. {@code b}
   * has a blank tip, {@code c} is not open, {@code d} has no revision/tip at all, so only {@code a}
   * survives.
   */
  @Test
  void listOpenChanges_parsesBareArray_returnsOnlyOpenChangesWithTip_inOrder() {
    String body =
        "["
            + "{\"id\":\"a\",\"status\":\"open\",\"revision\":{\"tip\":\"aaa\"}},"
            + "{\"id\":\"b\",\"status\":\"open\",\"revision\":{\"tip\":\"\"}},"
            + "{\"id\":\"c\",\"status\":\"merged\",\"revision\":{\"tip\":\"ccc\"}},"
            + "{\"id\":\"d\",\"status\":\"open\"}"
            + "]";
    wiremock.stubFor(
        get(urlEqualTo(CHANGES_URL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));

    List<PulsarClient.PulsarOpenChange> out = client.listOpenChanges(REPO);
    assertEquals(1, out.size());
    assertEquals("a", out.get(0).changeId());
    assertEquals("aaa", out.get(0).tip());
  }

  @Test
  void listOpenChanges_emptyArray_returnsEmptyList() {
    wiremock.stubFor(
        get(urlEqualTo(CHANGES_URL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));

    assertTrue(client.listOpenChanges(REPO).isEmpty());
  }

  @Test
  void listOpenChanges_openChangeWithBlankOrNullId_isSkipped() {
    String body =
        "["
            + "{\"id\":\"\",\"status\":\"open\",\"revision\":{\"tip\":\"t0\"}},"
            + "{\"status\":\"open\",\"revision\":{\"tip\":\"t1\"}},"
            + "{\"id\":\"keep\",\"status\":\"open\",\"revision\":{\"tip\":\"t2\"}}"
            + "]";
    wiremock.stubFor(
        get(urlEqualTo(CHANGES_URL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));

    List<PulsarClient.PulsarOpenChange> out = client.listOpenChanges(REPO);
    assertEquals(1, out.size());
    assertEquals("keep", out.get(0).changeId());
    assertEquals("t2", out.get(0).tip());
  }

  @Test
  void listOpenChanges_nonTwoXx_throwsPulsarApiExceptionCarryingStatus() {
    wiremock.stubFor(get(urlEqualTo(CHANGES_URL)).willReturn(aResponse().withStatus(500)));

    PulsarApiException ex =
        assertThrows(PulsarApiException.class, () -> client.listOpenChanges(REPO));
    assertEquals(500, ex.status());
  }

  @Test
  void listOpenChanges_malformedBody_throwsPulsarApiException() {
    wiremock.stubFor(
        get(urlEqualTo(CHANGES_URL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{not json")));

    assertThrows(PulsarApiException.class, () -> client.listOpenChanges(REPO));
  }

  // ── listChangeRefs (live bug: bare-array refs body, same defect #1297) ───────

  /**
   * Regression for the live bug: {@code /_pulsar/repos/<repo>/refs} returns a bare ARRAY (not
   * {@code {"refs":[...]}}), mixing heads/tags/change refs. listChangeRefs must parse the array and
   * return ONLY the {@code refs/pulsar/changes/*} entries with a non-blank oid. Against the old
   * RefsEnvelope code this body deserialized to START_ARRAY and threw PulsarApiException("malformed
   * JSON") every 30s tick — leaving the scanner unable to resolve any change tip.
   */
  @Test
  void listChangeRefs_parsesBareArray_returnsOnlyChangeRefsWithNonBlankOid() {
    String body =
        "["
            + "{\"name\":\"refs/heads/main\",\"oid\":\"aaa\"},"
            + "{\"name\":\"refs/pulsar/changes/c1\",\"oid\":\"bbb\"},"
            + "{\"name\":\"refs/pulsar/changes/c2\",\"oid\":\"\"},"
            + "{\"name\":\"refs/tags/v1\",\"oid\":\"ccc\"}"
            + "]";
    wiremock.stubFor(
        get(urlEqualTo(REFS_URL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(body)));

    assertEquals(Map.of("refs/pulsar/changes/c1", "bbb"), client.listChangeRefs(REPO));
  }

  @Test
  void listChangeRefs_emptyArray_returnsEmptyMap() {
    wiremock.stubFor(
        get(urlEqualTo(REFS_URL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("[]")));

    assertTrue(client.listChangeRefs(REPO).isEmpty());
  }

  @Test
  void listChangeRefs_nonTwoXx_throwsPulsarApiExceptionCarryingStatus() {
    wiremock.stubFor(get(urlEqualTo(REFS_URL)).willReturn(aResponse().withStatus(500)));

    PulsarApiException ex =
        assertThrows(PulsarApiException.class, () -> client.listChangeRefs(REPO));
    assertEquals(500, ex.status());
  }

  @Test
  void listChangeRefs_malformedBody_throwsPulsarApiException() {
    wiremock.stubFor(
        get(urlEqualTo(REFS_URL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{not json")));

    assertThrows(PulsarApiException.class, () -> client.listChangeRefs(REPO));
  }

  // ── postCheck: CI verdict via the change EVENTS api (EventKind::CiStatus), #1282 live 404 fix ──

  /**
   * Regression guard for the live 404: the verdict MUST be appended to the change EVENTS api
   * ({@code POST /_pulsar/ledger/<repo>/changes/<id>/events}) — NOT a non-existent {@code /checks}
   * route — with the node's {@code EventKind::CiStatus} body shape {@code
   * {"kind":"ci","check":"build","conclusion":"success"}}. Against the old {@code /checks}+{@code
   * {"name":..}} code this assertion fails (wrong path AND wrong field names). The {@code
   * details_url} arg is accepted for call-site symmetry but MUST NOT appear on the wire.
   */
  @Test
  void postCheck_success_postsCiEventToEventsPath_withKindCheckConclusion_noDetailsUrl() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    client.postCheck(
        REPO, CHANGE_ID, "build", CheckConclusion.SUCCESS, "https://titan.example.com/builds/9");

    // Exact path /events (never /checks) + exact CiStatus body discriminator/fields.
    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"kind\":\"ci\""))
            .withRequestBody(containing("\"check\":\"build\""))
            .withRequestBody(containing("\"conclusion\":\"success\"")));
    // The dead /checks route must never be hit, and the unknown details_url field must be absent.
    wiremock.verify(0, postRequestedFor(urlMatching(".*/checks$")));
    wiremock.verify(
        0, postRequestedFor(urlEqualTo(EVENTS_URL)).withRequestBody(containing("details_url")));
    wiremock.verify(
        0, postRequestedFor(urlEqualTo(EVENTS_URL)).withRequestBody(containing("\"name\":")));
  }

  @Test
  void postCheck_failure_emitsFailureConclusion() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    client.postCheck(REPO, CHANGE_ID, "build", CheckConclusion.FAILURE, null);

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"kind\":\"ci\""))
            .withRequestBody(containing("\"conclusion\":\"failure\"")));
  }

  @Test
  void postCheck_pending_emitsPendingConclusion() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    client.postCheck(REPO, CHANGE_ID, "build", CheckConclusion.PENDING, null);

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"pending\"")));
  }

  /**
   * UNSTABLE has no Pulsar-accepted token (the node rejects anything but {@code
   * pending|success|failure} with a 400), so it MUST collapse to {@code failure} on the wire — the
   * gate-safe honest mapping for a non-green build.
   */
  @Test
  void postCheck_unstable_collapsesToFailureOnTheWire() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(201)));

    assertEquals("failure", CheckConclusion.UNSTABLE.wire());

    client.postCheck(REPO, CHANGE_ID, "build", CheckConclusion.UNSTABLE, null);

    wiremock.verify(
        1,
        postRequestedFor(urlEqualTo(EVENTS_URL))
            .withRequestBody(containing("\"conclusion\":\"failure\"")));
  }

  @Test
  void postCheck_wireTokens_areExactlyTheNodeAcceptedSet() {
    assertEquals("pending", CheckConclusion.PENDING.wire());
    assertEquals("success", CheckConclusion.SUCCESS.wire());
    assertEquals("failure", CheckConclusion.FAILURE.wire());
    assertEquals("failure", CheckConclusion.UNSTABLE.wire());
  }

  @Test
  void postCheck_nonTwoXx_throwsPulsarApiExceptionCarryingStatus() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(404)));

    PulsarApiException ex =
        assertThrows(
            PulsarApiException.class,
            () -> client.postCheck(REPO, CHANGE_ID, "build", CheckConclusion.SUCCESS, null));
    assertEquals(404, ex.status());
  }

  @Test
  void postCheck_badRequest400_throwsPulsarApiExceptionCarryingStatus() {
    wiremock.stubFor(post(urlEqualTo(EVENTS_URL)).willReturn(aResponse().withStatus(400)));

    PulsarApiException ex =
        assertThrows(
            PulsarApiException.class,
            () -> client.postCheck(REPO, CHANGE_ID, "build", CheckConclusion.FAILURE, null));
    assertEquals(400, ex.status());
  }
}
