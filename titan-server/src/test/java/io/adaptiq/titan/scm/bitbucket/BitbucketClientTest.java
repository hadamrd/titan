package io.adaptiq.titan.scm.bitbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BitbucketClient} — 429 Retry-After single-retry, transport errors. */
class BitbucketClientTest {

  private static HttpClient http() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  }

  private static final BitbucketAuthProvider AUTH =
      BitbucketAuthProvider.appPassword("alice", "secret");

  @Test
  void rateLimited_429ThenSuccess_retriesExactlyOnce_honoursRetryAfterCapped() throws Exception {
    AtomicLong slept = new AtomicLong(-1);
    // Build a server whose first response is 429 (Retry-After: 3) and second is 200.
    int[] calls = {0};
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(
            rec -> {
              calls[0]++;
              if (calls[0] == 1) {
                return RecordingBitbucketServer.Reply.statusWithHeader(429, "Retry-After", "3");
              }
              return RecordingBitbucketServer.Reply.ok("{\"ok\":true}");
            })) {
      // maxRetryAfterMillis cap = 50ms, so the honoured sleep is min(3000, 50) = 50.
      BitbucketClient client = new BitbucketClient(http(), server.baseUrl(), slept::set, 50L);
      BitbucketClient.Response resp = client.send("GET", "/x", AUTH, null);

      assertEquals(200, resp.status(), "should return the post-retry success");
      assertEquals(2, calls[0], "must retry exactly once");
      assertEquals(50L, slept.get(), "honoured Retry-After must be capped at maxRetryAfterMillis");
    }
  }

  @Test
  void rateLimited_429Twice_givesUpGracefullyReturningSecond429() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(
            rec -> RecordingBitbucketServer.Reply.statusWithHeader(429, "Retry-After", "1"))) {
      BitbucketClient client = new BitbucketClient(http(), server.baseUrl(), ms -> {}, 10L);
      BitbucketClient.Response resp = client.send("POST", "/y", AUTH, "{}");
      assertEquals(429, resp.status(), "after one retry we give up and surface the 429");
      assertEquals(2, server.recorded().size(), "exactly two attempts, no infinite loop");
    }
  }

  @Test
  void transportError_throwsBitbucketApiException() {
    // Point at a port that is (almost certainly) closed.
    BitbucketClient client = new BitbucketClient(http(), "http://127.0.0.1:1", ms -> {}, 10L);
    BitbucketApiException ex =
        assertThrows(
            BitbucketApiException.class, () -> client.send("GET", "/whatever", AUTH, null));
    assertEquals(-1, ex.status());
    assertTrue(ex.getMessage().contains("/whatever"));
  }

  @Test
  void httpErrorStatus_returnedNotThrown() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.status(401))) {
      BitbucketClient client = new BitbucketClient(http(), server.baseUrl(), ms -> {}, 10L);
      BitbucketClient.Response resp = client.send("GET", "/z", AUTH, null);
      assertEquals(401, resp.status());
      assertTrue(!resp.isSuccess());
    }
  }
}
