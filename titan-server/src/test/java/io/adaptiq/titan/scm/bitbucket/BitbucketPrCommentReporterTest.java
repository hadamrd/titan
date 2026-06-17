package io.adaptiq.titan.scm.bitbucket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.build.BuildStateChangedEvent;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BitbucketPrCommentReporter} — sticky-comment create vs edit-in-place. */
class BitbucketPrCommentReporterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCOPE = BitbucketPrCommentReporter.CREDENTIALS_SCOPE;
  private static final String CRED_ID = "bb-cred";
  private static final long JOB_ID = 99L;
  private static final long BUILD_ID = 42L;
  private static final int PR_ID = 7;

  private BitbucketPrCommentReporter newReporter(RecordingBitbucketServer server) {
    FakeCredentialsService creds = new FakeCredentialsService();
    creds.put(SCOPE, CRED_ID, "alice:app-password-secret");
    BitbucketClientFactory factory =
        new BitbucketClientFactory(
            server.baseUrl(),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            0L);
    return new BitbucketPrCommentReporter(creds, factory, "https://titan.example.com", true);
  }

  private static BuildStateChangedEvent terminalEvent(String status) {
    String meta =
        "{\"workspace\":\"acme\",\"repoSlug\":\"widget\",\"prId\":"
            + PR_ID
            + ",\"bitbucketCredentialsId\":\""
            + CRED_ID
            + "\"}";
    return new BuildStateChangedEvent(BUILD_ID, status, "bitbucket:pullrequest", meta, JOB_ID, 3);
  }

  @Test
  void firstRun_noExistingComment_postsNew() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(
            rec -> {
              if (rec.method().equals("GET")) {
                return RecordingBitbucketServer.Reply.ok("{\"values\":[]}");
              }
              return RecordingBitbucketServer.Reply.created("{\"id\":555}");
            })) {
      newReporter(server).report(terminalEvent("SUCCESS"));

      RecordingBitbucketServer.Recorded post =
          server.firstMatching(
              "POST", "/2.0/repositories/acme/widget/pullrequests/" + PR_ID + "/comments");
      assertNotNull(post, "expected a POST to create the comment");
      JsonNode body = MAPPER.readTree(post.body());
      String raw = body.path("content").path("raw").asText();
      assertTrue(raw.contains(BitbucketPrCommentReporter.markerFor(JOB_ID)), "missing marker");
      assertTrue(raw.contains("SUCCESS"), "missing verdict");
      assertTrue(raw.contains("https://titan.example.com/builds/" + BUILD_ID), "missing run URL");
      // Basic auth header (app-password mode), never bearer.
      assertTrue(post.headers().get("Authorization").startsWith("Basic "));
    }
  }

  @Test
  void secondRun_existingCommentWithMarker_editsInPlace() throws Exception {
    String marker = BitbucketPrCommentReporter.markerFor(JOB_ID);
    String listBody =
        "{\"values\":[{\"id\":777,\"content\":{\"raw\":\"" + marker + "\\nold body\"}}]}";
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(
            rec -> {
              if (rec.method().equals("GET")) {
                return RecordingBitbucketServer.Reply.ok(listBody);
              }
              return RecordingBitbucketServer.Reply.ok("{\"id\":777}");
            })) {
      newReporter(server).report(terminalEvent("FAILED"));

      RecordingBitbucketServer.Recorded put =
          server.firstMatching(
              "PUT", "/2.0/repositories/acme/widget/pullrequests/" + PR_ID + "/comments/777");
      assertNotNull(put, "expected a PUT editing comment 777 in place");
      // And NO fresh POST was issued.
      assertFalse(
          server.recorded().stream().anyMatch(r -> r.method().equals("POST")),
          "must edit, not append a new comment");
    }
  }

  @Test
  void existingCommentOnSecondPage_followsNextCursor_editsInPlace() throws Exception {
    String marker = BitbucketPrCommentReporter.markerFor(JOB_ID);
    String commentsBase = "/2.0/repositories/acme/widget/pullrequests/" + PR_ID + "/comments";
    // Page 1: full of unrelated comments, NO marker, but a `next` cursor to page 2.
    String page1 =
        "{\"values\":[{\"id\":1,\"content\":{\"raw\":\"chatter\"}}],"
            + "\"next\":\"https://api.bitbucket.org"
            + commentsBase
            + "?page=2\"}";
    // Page 2: carries the sticky marker on comment 777 (no further `next`).
    String page2 =
        "{\"values\":[{\"id\":777,\"content\":{\"raw\":\"" + marker + "\\nold body\"}}]}";
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(
            rec -> {
              if (rec.method().equals("GET")) {
                return rec.path().contains("page=2")
                    ? RecordingBitbucketServer.Reply.ok(page2)
                    : RecordingBitbucketServer.Reply.ok(page1);
              }
              return RecordingBitbucketServer.Reply.ok("{\"id\":777}");
            })) {
      newReporter(server).report(terminalEvent("SUCCESS"));

      // Both pages were walked.
      assertTrue(
          server.recorded().stream()
              .anyMatch(r -> r.method().equals("GET") && r.path().contains("page=2")),
          "must follow the `next` cursor to page 2");
      // Marker found on page 2 → edit-in-place via PUT on 777, no fresh POST.
      assertNotNull(
          server.firstMatching("PUT", commentsBase + "/777"),
          "expected a PUT editing the page-2 comment in place");
      assertFalse(
          server.recorded().stream().anyMatch(r -> r.method().equals("POST")),
          "must edit the paginated comment, not append a new one");
    }
  }

  @Test
  void nonBitbucketTrigger_noHttpCall() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.created("{}"))) {
      String meta =
          "{\"workspace\":\"acme\",\"repoSlug\":\"widget\",\"prId\":7,\"bitbucketCredentialsId\":\""
              + CRED_ID
              + "\"}";
      newReporter(server)
          .report(
              new BuildStateChangedEvent(BUILD_ID, "SUCCESS", "github-app:push", meta, JOB_ID, 1));
      assertEquals(0, server.recorded().size());
    }
  }

  @Test
  void nonTerminalStatus_noHttpCall() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.created("{}"))) {
      newReporter(server).report(terminalEvent("RUNNING"));
      assertEquals(0, server.recorded().size());
    }
  }

  @Test
  void prClosed_404OnPost_swallowed_noThrow() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(
            rec ->
                rec.method().equals("GET")
                    ? RecordingBitbucketServer.Reply.ok("{\"values\":[]}")
                    : RecordingBitbucketServer.Reply.status(404))) {
      assertDoesNotThrow(() -> newReporter(server).report(terminalEvent("SUCCESS")));
    }
  }

  @Test
  void unauthorized_401_swallowed_noThrow() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.status(401))) {
      assertDoesNotThrow(() -> newReporter(server).report(terminalEvent("SUCCESS")));
    }
  }
}
