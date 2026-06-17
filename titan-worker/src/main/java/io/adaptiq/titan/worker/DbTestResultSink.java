package io.adaptiq.titan.worker;

import io.adaptiq.titan.worker.step.TestResultSink;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The worker's {@link TestResultSink} — the bridge from a {@link
 * io.adaptiq.titan.worker.step.StepHandler}'s {@code submit(...)} call to the {@code
 * titan.test_result} table (issue #298). Same pattern as {@link DbArtifactSink}: the sink owns the
 * build/node coordinates + the DB handle so a handler never has to (design/32 §3.2); one sink is
 * built per task by {@code TaskExecutor}.
 *
 * <p>Unlike {@link DbArtifactSink}, an {@code IOException} here is <em>not</em> a step failure: the
 * {@code junit} step's contract makes per-case persistence purely additive (the tally and the
 * fail-on-failure policy are the contract). The handler logs and swallows.
 */
final class DbTestResultSink implements TestResultSink {

  private final WorkerDb db;
  private final long buildId;
  private final String nodeId;

  DbTestResultSink(WorkerDb db, long buildId, String nodeId) {
    this.db = db;
    this.buildId = buildId;
    this.nodeId = nodeId;
  }

  @Override
  public void submit(List<Case> cases) throws IOException {
    List<WorkerDb.TestCaseRow> rows = new ArrayList<>(cases.size());
    for (Case c : cases) {
      rows.add(
          new WorkerDb.TestCaseRow(
              c.suite(), c.className(), c.name(), c.status(), c.durationMs(), c.failureMessage()));
    }
    try {
      db.insertTestResults(buildId, nodeId, rows);
    } catch (SQLException e) {
      throw new IOException(
          "could not record "
              + rows.size()
              + " test-case row(s) for build "
              + buildId
              + " node "
              + nodeId
              + ": "
              + e.getMessage(),
          e);
    }
  }
}
