package io.adaptiq.titan.scm.bitbucket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.adaptiq.titan.scm.Finding;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link BitbucketInlineReviewReporter} — Finding → inline JSON + fallback. */
class BitbucketInlineReviewReporterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String SCOPE = BitbucketInlineReviewReporter.CREDENTIALS_SCOPE;
  private static final String CRED_ID = "bb-cred";
  private static final BitbucketPrContext CTX =
      new BitbucketPrContext("acme", "widget", 7, CRED_ID);

  private BitbucketInlineReviewReporter reporter(RecordingBitbucketServer server) {
    FakeCredentialsService creds = new FakeCredentialsService();
    creds.put(SCOPE, CRED_ID, "alice:app-pw");
    BitbucketClientFactory factory =
        new BitbucketClientFactory(
            server.baseUrl(),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            0L);
    return new BitbucketInlineReviewReporter(creds, factory, true);
  }

  // ── experimental flag: disabled → no-op, no HTTP ─────────────────────────────

  @Test
  void disabledByFlag_noop_noHttp() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.created("{\"id\":1}"))) {
      FakeCredentialsService creds = new FakeCredentialsService();
      creds.put(SCOPE, CRED_ID, "alice:app-pw");
      BitbucketClientFactory factory =
          new BitbucketClientFactory(
              server.baseUrl(),
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
              0L);
      // enabled=false → the experimental capability is off; never touches the network.
      BitbucketInlineReviewReporter disabled =
          new BitbucketInlineReviewReporter(creds, factory, false);
      BitbucketInlineReviewReporter.Result result =
          disabled.postFindings(
              CTX, List.of(new Finding("src/foo.py", 1, Finding.Severity.MAJOR, "x")));
      assertEquals(BitbucketInlineReviewReporter.Result.NOOP, result);
      assertEquals(0, server.recorded().size(), "disabled reporter must not call Bitbucket");
    }
  }

  // ── payload shape ────────────────────────────────────────────────────────────

  @Test
  void inlinePayload_carriesPathAndLineAndSeverity() throws Exception {
    Finding f = new Finding("src/foo.py", 12, Finding.Severity.MAJOR, "SQL injection risk");
    JsonNode body = MAPPER.readTree(BitbucketInlineReviewReporter.inlinePayload(f));
    assertEquals("src/foo.py", body.path("inline").path("path").asText());
    assertEquals(12, body.path("inline").path("to").asInt());
    String raw = body.path("content").path("raw").asText();
    assertTrue(raw.contains("SQL injection risk"));
    assertTrue(raw.contains("MAJOR"));
  }

  // ── multi-finding batching (happy path) ──────────────────────────────────────

  @Test
  void multipleFindings_postOneInlineCommentEach() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.created("{\"id\":1}"))) {
      List<Finding> findings =
          List.of(
              new Finding("src/foo.py", 1, Finding.Severity.MINOR, "a"),
              new Finding("src/foo.py", 2, Finding.Severity.MAJOR, "b"),
              new Finding("src/foo.py", 3, Finding.Severity.CRITICAL, "c"));
      BitbucketInlineReviewReporter.Result result = reporter(server).postFindings(CTX, findings);

      assertEquals(3, result.inlinePosted());
      assertEquals(0, result.fellBackToSummary());
      assertFalse(result.summaryPosted());
      assertEquals(3, server.recorded().size());
      assertTrue(server.recorded().stream().allMatch(r -> r.method().equals("POST")));
    }
  }

  // ── file-renamed sad path: 400 → fallback to summary comment, no crash ────────

  @Test
  void findingNotInDiff_400_fallsBackToSummaryComment() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(
            rec -> {
              JsonNode body;
              try {
                body = MAPPER.readTree(rec.body());
              } catch (Exception e) {
                return RecordingBitbucketServer.Reply.status(500);
              }
              // Inline comments carry an "inline" node; the summary fallback does not.
              boolean isInline = body.has("inline");
              if (isInline && body.path("inline").path("path").asText().equals("renamed.py")) {
                return RecordingBitbucketServer.Reply.status(400);
              }
              return RecordingBitbucketServer.Reply.created("{\"id\":1}");
            })) {
      List<Finding> findings =
          List.of(
              new Finding("src/foo.py", 1, Finding.Severity.MAJOR, "real inline"),
              new Finding("renamed.py", 9, Finding.Severity.CRITICAL, "renamed file finding"));
      BitbucketInlineReviewReporter.Result result = reporter(server).postFindings(CTX, findings);

      assertEquals(1, result.inlinePosted(), "one inline should succeed");
      assertEquals(1, result.fellBackToSummary(), "renamed-file finding falls back");
      assertTrue(result.summaryPosted(), "a summary comment must be posted for the fallback");

      // The summary POST must contain the renamed-file finding text and NOT carry an inline node.
      RecordingBitbucketServer.Recorded summary =
          server.recorded().stream()
              .filter(
                  r -> {
                    try {
                      return r.method().equals("POST")
                          && !MAPPER.readTree(r.body()).has("inline")
                          && MAPPER
                              .readTree(r.body())
                              .path("content")
                              .path("raw")
                              .asText()
                              .contains("renamed file finding");
                    } catch (Exception e) {
                      return false;
                    }
                  })
              .findFirst()
              .orElse(null);
      assertNotNull(summary, "expected one fallback summary comment");
    }
  }

  // ── line-less finding → goes straight to summary, never attempted inline ──────

  @Test
  void findingWithoutLineAnchor_goesToSummaryDirectly() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.created("{\"id\":1}"))) {
      List<Finding> findings =
          List.of(new Finding("src/foo.py", 0, Finding.Severity.INFO, "file-level note"));
      BitbucketInlineReviewReporter.Result result = reporter(server).postFindings(CTX, findings);

      assertEquals(0, result.inlinePosted());
      assertEquals(1, result.fellBackToSummary());
      assertTrue(result.summaryPosted());
      // Exactly one HTTP call (the summary) — no inline attempt for a line-less finding.
      assertEquals(1, server.recorded().size());
    }
  }

  // ── empty findings → no-op, no HTTP ──────────────────────────────────────────

  @Test
  void emptyFindings_noop() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.created("{}"))) {
      BitbucketInlineReviewReporter.Result result = reporter(server).postFindings(CTX, List.of());
      assertEquals(BitbucketInlineReviewReporter.Result.NOOP, result);
      assertEquals(0, server.recorded().size());
    }
  }

  // ── unresolvable credential → no-op, no crash ────────────────────────────────

  @Test
  void unresolvableCredential_noop() throws Exception {
    try (RecordingBitbucketServer server =
        new RecordingBitbucketServer(rec -> RecordingBitbucketServer.Reply.created("{}"))) {
      FakeCredentialsService creds = new FakeCredentialsService(); // empty
      BitbucketClientFactory factory =
          new BitbucketClientFactory(
              server.baseUrl(),
              HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
              0L);
      BitbucketInlineReviewReporter r = new BitbucketInlineReviewReporter(creds, factory, true);
      BitbucketInlineReviewReporter.Result result =
          r.postFindings(CTX, List.of(new Finding("a.py", 1, Finding.Severity.INFO, "x")));
      assertEquals(BitbucketInlineReviewReporter.Result.NOOP, result);
      assertEquals(0, server.recorded().size());
    }
  }
}
