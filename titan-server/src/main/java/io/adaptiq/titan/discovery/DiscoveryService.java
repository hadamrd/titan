package io.adaptiq.titan.discovery;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Optional;

/**
 * SCM-polling discovery service — the standalone periodic poller (closes #275).
 *
 * <p>Reads {@code scm.url} / {@code scm.branch} from each enabled job's {@code config_json},
 * resolves the remote HEAD SHA via {@link GitHeadResolver}, and triggers a build when the SHA
 * differs from the per-job {@code last_seen_sha} recorded in {@code titan.discovery_state}. The
 * driver is a Quarkus {@code @Scheduled} tick in {@link DiscoveryScheduler}; tests and operator
 * one-shots call {@link #pollAll()} / {@link #pollOne(long)} directly.
 *
 * <p>Errors are absorbed per-job — a failure on one job does not stop the loop and does not
 * overwrite the job's {@code last_seen_sha} (so a transient remote outage does not trigger a
 * phantom rebuild when the network recovers).
 */
public interface DiscoveryService {

  /**
   * Poll every enabled job that declares an {@code scm} block. Returns the number of builds
   * triggered.
   */
  int pollAll();

  /**
   * Poll one job by id. Returns the new build id when a build was triggered (HEAD SHA changed) or
   * empty when the job has no scm config, the SHA was unchanged, or the lookup failed.
   *
   * @throws io.adaptiq.titan.job.JobNotFoundException if no job with {@code jobId} exists.
   */
  @NonNull
  Optional<Long> pollOne(long jobId);

  /** Last successful or failed poll timestamp for the job, or empty if it has never been polled. */
  @NonNull
  Optional<Instant> lastPollOf(long jobId);
}
