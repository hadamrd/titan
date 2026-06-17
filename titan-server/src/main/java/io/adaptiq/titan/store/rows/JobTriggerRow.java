package io.adaptiq.titan.store.rows;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Mutable POJO mapping to a row in {@code titan.job_triggers} — the per-trigger runtime state the
 * firing engine accumulates (design/50 D6). The trigger <em>definition</em> (cron spec, tz) lives
 * in {@code titan.jobs.config_json}, not here. Natural key: {@code (jobId, triggerId)}.
 */
public class JobTriggerRow {
  public long jobId;

  /** The trigger's stable minted UUID — matches the {@code id} in the {@code config_json} DTO. */
  public String triggerId;

  /** When the trigger last fired (or last had a satisfied occurrence). Null → never fired. */
  @Nullable public Instant lastFiredAt;

  /** The last evaluation error, if any — surfaced for diagnostics. Null → no error. */
  @Nullable public String lastError;
}
