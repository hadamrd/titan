package io.adaptiq.titan.api.dto;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.TestResultRow;

/**
 * REST projection of one row in {@code titan.test_result} (issue #296).
 *
 * <p><strong>Wire-format invariant:</strong> internal columns ({@code nodeId}, {@code buildId},
 * {@code createdAt}) are <em>never</em> projected — they are server-side implementation detail. A
 * client only sees: the synthetic id, the {@code <testsuite>} name, the {@code <testcase>}'s
 * className and name, the {@code PASSED}/{@code FAILED}/{@code SKIPPED} status, the duration in
 * milliseconds, and — only for {@code FAILED} rows — the {@code <failure>}/{@code <error>} text.
 *
 * <p>{@link #failureMessage} is {@code null} for non-failures and is stripped from the JSON wire
 * format via {@link JsonInclude#NON_NULL}, so the field doesn't appear on the wire for {@code
 * PASSED}/{@code SKIPPED} rows.
 */
@JsonInclude(NON_NULL)
public record TestRowDto(
    long id,
    String suite,
    String className,
    String name,
    String status,
    long durationMs,
    @Nullable String failureMessage) {

  public static TestRowDto from(TestResultRow row) {
    boolean isFailed = "FAILED".equals(row.status);
    return new TestRowDto(
        row.id,
        row.suite,
        row.className,
        row.name,
        row.status,
        row.durationMs,
        isFailed ? row.failureMessage : null);
  }
}
