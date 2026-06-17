package io.adaptiq.titan.notifications;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.BuildRow;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Pure-function digest builder + HTML renderer (issue #1103).
 *
 * <p>Why pure: every input is a value (the build rows, the job rows, the window bounds) and every
 * output is a value (the {@link Digest} record, the rendered HTML string). No I/O, no DAOs, no
 * clocks. This is what makes the golden-snapshot test reliable: feed it a synthetic build history,
 * get back a deterministic HTML string, diff against a checked-in file.
 *
 * <p>The renderer reads {@code templates/digest.html.ftl} off the classpath once at class init.
 * Despite the {@code .ftl} extension (preserved from the spec's file pointer), this is NOT a
 * FreeMarker template — pulling in FreeMarker for one digest email would bloat the runtime
 * classpath. Instead the template uses a tiny brace-style placeholder syntax ({@code {{key}}} and
 * {@code {{#rows}}...{{/rows}}}) that this class evaluates in &lt;100 lines. The result is a
 * stable, byte-deterministic render — exactly what a golden test needs.
 */
@Singleton
public final class DigestBuilder {

  private static final DateTimeFormatter TS_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);
  private static final DateTimeFormatter DAY_FMT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

  static final int LONGEST_BUILDS_TOP_N = 5;
  static final String FAILED_STATUS = "FAILED";

  private final String template;

  public DigestBuilder() {
    this.template = loadTemplate();
  }

  /** Build the {@link Digest} from raw build + job rows. Caller pre-fetches the window. */
  @NonNull
  public Digest build(
      @NonNull Instant windowStart,
      @NonNull Instant windowEnd,
      @NonNull List<BuildRow> builds,
      @NonNull List<JobRow> jobs) {
    Objects.requireNonNull(windowStart, "windowStart");
    Objects.requireNonNull(windowEnd, "windowEnd");
    Objects.requireNonNull(builds, "builds");
    Objects.requireNonNull(jobs, "jobs");

    Map<Long, String> jobNameById = new HashMap<>();
    for (JobRow j : jobs) {
      jobNameById.put(j.id, displayName(j));
    }

    // --- group by job to count failures + last-failure-at ---
    Map<Long, FailAcc> byJob = new HashMap<>();
    int totalFailures = 0;
    for (BuildRow b : builds) {
      FailAcc acc = byJob.computeIfAbsent(b.jobId, k -> new FailAcc());
      acc.total++;
      if (FAILED_STATUS.equals(b.status)) {
        acc.failures++;
        totalFailures++;
        if (b.finishedAt != null
            && (acc.lastFailureAt == null || b.finishedAt.isAfter(acc.lastFailureAt))) {
          acc.lastFailureAt = b.finishedAt;
        }
      }
    }

    List<Digest.FailingJob> failingJobs = new ArrayList<>();
    for (Map.Entry<Long, FailAcc> e : byJob.entrySet()) {
      FailAcc acc = e.getValue();
      if (acc.failures == 0) {
        continue;
      }
      String name = jobNameById.getOrDefault(e.getKey(), "job#" + e.getKey());
      Instant lastFail = acc.lastFailureAt != null ? acc.lastFailureAt : windowEnd;
      failingJobs.add(new Digest.FailingJob(e.getKey(), name, acc.failures, acc.total, lastFail));
    }
    failingJobs.sort(
        Comparator.comparingInt(Digest.FailingJob::failureCount)
            .reversed()
            .thenComparing(Digest.FailingJob::jobName));

    // --- top N longest builds across the window (any terminal status) ---
    List<Digest.BuildEntry> longest =
        builds.stream()
            .filter(b -> b.durationMs != null && b.finishedAt != null)
            .sorted(Comparator.comparingLong((BuildRow b) -> b.durationMs).reversed())
            .limit(LONGEST_BUILDS_TOP_N)
            .map(
                b ->
                    new Digest.BuildEntry(
                        b.id,
                        b.jobId,
                        jobNameById.getOrDefault(b.jobId, "job#" + b.jobId),
                        b.buildNumber,
                        b.status,
                        Duration.ofMillis(b.durationMs),
                        b.finishedAt))
            .collect(Collectors.toList());

    return new Digest(windowStart, windowEnd, builds.size(), totalFailures, failingJobs, longest);
  }

  /** Render the digest to a self-contained HTML document. */
  @NonNull
  public String renderHtml(@NonNull Digest d) {
    Objects.requireNonNull(d, "digest");

    String failingRows;
    if (d.failingJobs().isEmpty()) {
      failingRows = "<tr><td colspan=\"4\" class=\"muted\">No failing jobs in window.</td></tr>";
    } else {
      StringBuilder sb = new StringBuilder();
      for (Digest.FailingJob j : d.failingJobs()) {
        sb.append("<tr>")
            .append("<td>")
            .append(escape(j.jobName()))
            .append("</td>")
            .append("<td class=\"num\">")
            .append(j.failureCount())
            .append("</td>")
            .append("<td class=\"num\">")
            .append(j.totalCount())
            .append("</td>")
            .append("<td>")
            .append(TS_FMT.format(j.lastFailureAt()))
            .append("</td>")
            .append("</tr>\n");
      }
      failingRows = sb.toString().stripTrailing();
    }

    String longestRows;
    if (d.longestBuilds().isEmpty()) {
      longestRows = "<tr><td colspan=\"4\" class=\"muted\">No builds in window.</td></tr>";
    } else {
      StringBuilder sb = new StringBuilder();
      for (Digest.BuildEntry b : d.longestBuilds()) {
        sb.append("<tr>")
            .append("<td>")
            .append(escape(b.jobName()))
            .append(" #")
            .append(b.buildNumber())
            .append("</td>")
            .append("<td>")
            .append(escape(b.status()))
            .append("</td>")
            .append("<td class=\"num\">")
            .append(formatDuration(b.duration()))
            .append("</td>")
            .append("<td>")
            .append(TS_FMT.format(b.finishedAt()))
            .append("</td>")
            .append("</tr>\n");
      }
      longestRows = sb.toString().stripTrailing();
    }

    Map<String, String> vars = new HashMap<>();
    vars.put("dayLabel", DAY_FMT.format(d.windowEnd()));
    vars.put("windowStart", TS_FMT.format(d.windowStart()));
    vars.put("windowEnd", TS_FMT.format(d.windowEnd()));
    vars.put("totalBuilds", Integer.toString(d.totalBuilds()));
    vars.put("totalFailures", Integer.toString(d.totalFailures()));
    vars.put("failingCount", Integer.toString(d.failingJobs().size()));
    vars.put("failingRows", failingRows);
    vars.put("longestRows", longestRows);

    return interpolate(template, vars);
  }

  /** Subject line used for the email — short, scannable, with the failing-job count. */
  @NonNull
  public String renderSubject(@NonNull Digest d) {
    return "[titan] daily digest "
        + DAY_FMT.format(d.windowEnd())
        + " — "
        + d.totalFailures()
        + " failure"
        + (d.totalFailures() == 1 ? "" : "s")
        + " across "
        + d.failingJobs().size()
        + " job"
        + (d.failingJobs().size() == 1 ? "" : "s");
  }

  // ---------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------

  private static String displayName(JobRow j) {
    if (j.displayName != null && !j.displayName.isBlank()) {
      return j.displayName;
    }
    return j.fullName;
  }

  private static String escape(String s) {
    StringBuilder out = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  static String formatDuration(Duration d) {
    long s = d.getSeconds();
    long h = s / 3600;
    long m = (s % 3600) / 60;
    long sec = s % 60;
    if (h > 0) {
      return String.format("%dh%02dm%02ds", h, m, sec);
    }
    if (m > 0) {
      return String.format("%dm%02ds", m, sec);
    }
    return sec + "s";
  }

  private static String interpolate(String tmpl, Map<String, String> vars) {
    StringBuilder out = new StringBuilder(tmpl.length() + 256);
    int i = 0;
    while (i < tmpl.length()) {
      int open = tmpl.indexOf("{{", i);
      if (open < 0) {
        out.append(tmpl, i, tmpl.length());
        break;
      }
      out.append(tmpl, i, open);
      int close = tmpl.indexOf("}}", open + 2);
      if (close < 0) {
        out.append(tmpl, open, tmpl.length());
        break;
      }
      String key = tmpl.substring(open + 2, close).trim();
      String v = vars.get(key);
      if (v == null) {
        // Leave the placeholder verbatim so missing keys are loud, not silent.
        out.append("{{").append(key).append("}}");
      } else {
        out.append(v);
      }
      i = close + 2;
    }
    return out.toString();
  }

  private static String loadTemplate() {
    try (var in = DigestBuilder.class.getResourceAsStream("/templates/digest.html.ftl")) {
      if (in == null) {
        throw new IllegalStateException("classpath:/templates/digest.html.ftl missing");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("failed to load digest template", e);
    }
  }

  private static final class FailAcc {
    int total;
    int failures;
    Instant lastFailureAt;
  }
}
