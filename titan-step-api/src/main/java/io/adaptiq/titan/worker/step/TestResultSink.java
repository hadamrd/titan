package io.adaptiq.titan.worker.step;

import java.io.IOException;
import java.util.List;

/**
 * The handler-facing collaborator for publishing parsed test-case results — the {@code junit} side
 * of the {@link StepRequest} contract (issue #298). Same pattern as {@link ArtifactSink} and {@link
 * OutputSink}: a {@link StepHandler} <em>declares</em> intent through the sink; the <em>worker</em>
 * owns the database write. A handler never holds a DB connection — its classloader could not reach
 * one anyway (design/32 §3.2).
 *
 * <p>The {@code junit} step archives the raw surefire XMLs through the {@link ArtifactSink}. The
 * sink writes per-case rows directly to {@code titan.test_result} so the REST API and the UI
 * test-results panel (#296) can read them without going back to the XML.
 */
@FunctionalInterface
public interface TestResultSink {

  /**
   * One parsed JUnit {@code <testcase>} — the row shape the sink writes. Carried as a record so the
   * SPI has no dependency on the controller-side row POJO (which lives in {@code titan-server} and
   * would create a back-edge against the layering).
   *
   * @param suite the {@code <testsuite name=…>} the case was reported under
   * @param className the {@code <testcase classname=…>}
   * @param name the {@code <testcase name=…>}
   * @param status one of {@code PASSED} / {@code FAILED} / {@code SKIPPED}
   * @param durationMs the {@code <testcase time=…>} converted to milliseconds, or {@code 0} when
   *     the attribute was missing / unparseable
   * @param failureMessage the {@code <failure>}/{@code <error>} body (message + stack), or {@code
   *     null} for non-failures
   */
  record Case(
      String suite,
      String className,
      String name,
      String status,
      long durationMs,
      String failureMessage) {}

  /**
   * Submit the parsed cases of one {@code junit} step's worth of reports. Called <em>once</em> per
   * step (not per file) so the worker can write the batch in one transaction. A re-run of the step
   * submits the same set again — the sink is expected to clear-then-insert the build's rows for
   * that step or otherwise converge on the new state (the worker's {@code DbTestResultSink} does
   * so).
   *
   * @param cases the cases to record; never {@code null}, may be empty
   * @throws IOException if the cases could not be persisted; the handler treats this as best-effort
   *     (per the {@code junit} step contract, archiving + persistence are additive and never fail a
   *     step whose tests parsed cleanly)
   */
  void submit(List<Case> cases) throws IOException;

  /**
   * The sink a {@link StepRequest} carries when the deployment has no controller-side test-result
   * store wired (a TCK test, a worker run against a stub DB). It is a silent no-op: persistence of
   * parsed cases is additive — the step's pass/fail policy and its archived XMLs are the contract;
   * recording rows is a bonus. The handler still archives through the {@link ArtifactSink}, which
   * has its own loud {@code UNCONFIGURED}.
   */
  TestResultSink UNCONFIGURED = cases -> {};
}
