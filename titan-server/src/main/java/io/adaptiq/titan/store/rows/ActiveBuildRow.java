package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * A read-only projection of one in-flight build — a {@code titan.builds} row whose status is
 * non-terminal ({@code QUEUED} or {@code RUNNING}), joined to its job.
 *
 * <p>This is not a table mapping; it is the result shape of {@code BuildDao.listActive()}, used by
 * {@code TitanExecutorsWidget} to render what is actually executing. A build is the stable unit a
 * user expects to "see running" — it lives for the whole pipeline, unlike the individual {@code
 * task_queue} rows (ORCHESTRATE / EXECUTE_COMMAND) that flicker through in seconds.
 */
public class ActiveBuildRow {
  /** {@code titan.builds.id}. */
  public long id;

  /** {@code titan.builds.build_number}. */
  public int buildNumber;

  /** {@code QUEUED} or {@code RUNNING}. */
  @Nullable public String status;

  /** When the build was enqueued — elapsed-time basis while still {@code QUEUED}. */
  @Nullable public Instant queuedAt;

  /** When the build started running — elapsed-time basis once {@code RUNNING}. */
  @Nullable public Instant startedAt;

  /** {@code titan.jobs.full_name} — the job's full name (used to build the URL). */
  @Nullable public String jobFullName;

  /** {@code titan.jobs.display_name} — human label; may be null, fall back to full name. */
  @Nullable public String jobDisplayName;
}
