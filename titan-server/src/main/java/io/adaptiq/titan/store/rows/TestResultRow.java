package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.test_result} (issue #298) — one parsed JUnit {@code
 * <testcase>} of a build. Written by the worker's {@code DbTestResultSink} when the {@code junit}
 * step parses surefire XML; read by the controller's REST {@code TestResultsApi} (follow-up) and
 * rendered by the build page's tests panel (#296).
 *
 * <p>Conventions match {@link ArtifactRow}: public mutable fields (no getters/setters),
 * {@code @Nullable} only on columns the schema declares nullable. JDBI {@code @RegisterFieldMapper}
 * fills these in by name from the lower_snake_case columns via JDBI's built-in mapping.
 */
public class TestResultRow {
  public long id;
  public long buildId;

  /** The {@code flow_nodes.node_id} the {@code junit} step ran on. */
  public String nodeId;

  /** The {@code <testsuite name=…>}. */
  public String suite;

  /** The {@code <testcase classname=…>}. */
  public String className;

  /** The {@code <testcase name=…>}. */
  public String name;

  /** One of {@code PASSED} / {@code FAILED} / {@code SKIPPED} (CHECK-constrained). */
  public String status;

  public long durationMs;

  /** {@code <failure>}/{@code <error>} message + stack — {@code null} for non-failures. */
  @Nullable public String failureMessage;

  public Instant createdAt;
}
