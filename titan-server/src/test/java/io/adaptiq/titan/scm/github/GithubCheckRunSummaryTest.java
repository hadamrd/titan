package io.adaptiq.titan.scm.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.FlowNodeRow;
import io.adaptiq.titan.store.rows.JobRow;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit-tests for the pure formatting helpers in {@link GithubCheckRunSummary} (closes #965). */
class GithubCheckRunSummaryTest {

  @Test
  void formatDuration_underAMinute_printsSecondsOnly() {
    assertEquals("47s", GithubCheckRunSummary.formatDuration(47_000L));
  }

  @Test
  void formatDuration_overAMinute_printsMinutesAndSeconds() {
    assertEquals("1m 23s", GithubCheckRunSummary.formatDuration(83_000L));
  }

  @Test
  void formatDuration_overAnHour_printsHoursMinutesSeconds() {
    assertEquals("1h 1m 1s", GithubCheckRunSummary.formatDuration(3_661_000L));
  }

  @Test
  void formatDuration_nullOrZero_isNotAvailable() {
    assertEquals("n/a", GithubCheckRunSummary.formatDuration(null));
    assertEquals("n/a", GithubCheckRunSummary.formatDuration(0L));
    assertEquals("n/a", GithubCheckRunSummary.formatDuration(-1L));
  }

  @Test
  void title_includesPipelineAndOutcome() {
    assertEquals("my-pipeline · succeeded", GithubCheckRunSummary.title("my-pipeline", "SUCCESS"));
    assertEquals("my-pipeline · failed", GithubCheckRunSummary.title("my-pipeline", "FAILED"));
    assertEquals("my-pipeline · aborted", GithubCheckRunSummary.title("my-pipeline", "ABORTED"));
    assertEquals("my-pipeline · unstable", GithubCheckRunSummary.title("my-pipeline", "UNSTABLE"));
  }

  @Test
  void summary_includesPipelineDurationStageCount() {
    String out =
        GithubCheckRunSummary.summary(
            "deploy", 90_000L, 5, List.of(), "https://titan.example.com/builds/42");
    assertTrue(out.contains("**Pipeline:** deploy"));
    assertTrue(out.contains("**Duration:** 1m 30s"));
    assertTrue(out.contains("**Stages:** 5"));
    // No failed-stages section when the list is empty.
    assertFalse(out.contains("Failed stages"));
  }

  @Test
  void summary_listsFailedStageNames() {
    String out =
        GithubCheckRunSummary.summary(
            "deploy",
            90_000L,
            5,
            List.of("compile", "test:unit"),
            "https://titan.example.com/builds/42");
    assertTrue(out.contains("**Failed stages:**"));
    assertTrue(out.contains("- compile"));
    assertTrue(out.contains("- test:unit"));
  }

  @Test
  void truncate_undercapPassesThrough() {
    String body = "small body";
    assertEquals(body, GithubCheckRunSummary.truncate(body, "https://titan/builds/1"));
  }

  @Test
  void truncate_overcapAppendsDetailsUrlSuffixAndFitsUnderCap() {
    StringBuilder huge = new StringBuilder(GithubCheckRunSummary.MAX_SUMMARY_CHARS + 5000);
    for (int i = 0; i < GithubCheckRunSummary.MAX_SUMMARY_CHARS + 5000; i++) {
      huge.append('x');
    }
    String detailsUrl = "https://titan.example.com/builds/42";
    String truncated = GithubCheckRunSummary.truncate(huge.toString(), detailsUrl);

    assertTrue(
        truncated.length() <= GithubCheckRunSummary.MAX_SUMMARY_CHARS,
        "truncated must fit under the cap, got " + truncated.length());
    assertTrue(
        truncated.endsWith("…see " + detailsUrl + " for full output"),
        "must end with the details-url suffix: " + truncated.substring(truncated.length() - 100));
  }

  @Test
  void failedStageNames_skipsNonFailedAndUsesDisplayName() {
    FlowNodeRow ok = node("stage:1", "compile", "SUCCESS");
    FlowNodeRow bad1 = node("stage:2", "test:unit", "FAILED");
    FlowNodeRow badNoDisplay = node("stage:3", null, "FAILED");
    FlowNodeRow skipped = node("stage:4", "deploy", "SKIPPED");

    List<String> out =
        GithubCheckRunSummary.failedStageNames(List.of(ok, bad1, badNoDisplay, skipped));

    assertEquals(List.of("test:unit", "stage:3"), out);
  }

  @Test
  void pipelineName_prefersBuildDisplayNameThenJobDisplayNameThenFullName() {
    BuildRow b = new BuildRow();
    JobRow j = new JobRow();
    j.fullName = "acme/widget";
    assertEquals("acme/widget", GithubCheckRunSummary.pipelineName(b, j));

    j.displayName = "Widget Builder";
    assertEquals("Widget Builder", GithubCheckRunSummary.pipelineName(b, j));

    b.displayName = "Release 1.2.3";
    assertEquals("Release 1.2.3", GithubCheckRunSummary.pipelineName(b, j));
  }

  @Test
  void pipelineName_blankFallsBackToTitanConstant() {
    assertEquals("Titan", GithubCheckRunSummary.pipelineName(null, null));
    BuildRow b = new BuildRow();
    b.displayName = "   ";
    JobRow j = new JobRow();
    j.displayName = "";
    j.fullName = " ";
    assertEquals("Titan", GithubCheckRunSummary.pipelineName(b, j));
  }

  private static FlowNodeRow node(String id, String display, String status) {
    FlowNodeRow n = new FlowNodeRow();
    n.nodeId = id;
    n.displayName = display;
    n.status = status;
    n.nodeType = "STAGE";
    return n;
  }
}
