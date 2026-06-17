package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.discovery_state} — per-job SCM polling state
 * recorded by the {@link io.adaptiq.titan.discovery.DiscoveryService}.
 *
 * <p>One row per job that declares an {@code scm} block in its {@code config_json}. The {@code
 * lastSeenSha} column drives the trigger decision: a poll resolves the remote HEAD SHA, and a build
 * is enqueued only when it differs from {@code lastSeenSha} (or no row exists yet).
 */
public class DiscoveryStateRow {
  public long jobId;

  @Nullable public String lastSeenSha;

  @Nullable public Instant lastPolledAt;

  @Nullable public String lastStatus;

  @Nullable public String lastError;

  public Instant updatedAt;
}
