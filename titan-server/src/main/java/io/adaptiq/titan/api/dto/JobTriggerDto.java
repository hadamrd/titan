package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Wire DTO for {@code GET /api/v1/jobs/{id}/triggers} — closes #725.
 *
 * <p>One row per trigger defined on the job, joining the parsed trigger definition (from {@code
 * titan.jobs.config_json}) with the per-trigger runtime state stored in {@code titan.job_triggers}
 * (last fired / last error).
 *
 * <p>{@code type} is a closed discriminated-union string (see {@link Type}); callers branch on it,
 * never on URL or field sniffing (feedback_principled_typed_design). {@code expression} is the cron
 * spec for {@code "cron"} entries; null for non-cron types. {@code nextFireAt} is optional —
 * computed for cron triggers via the vendored cron grammar, absent for github / manual.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobTriggerDto(
    @NonNull String id,
    @NonNull String type,
    @Nullable String expression,
    @Nullable Instant lastFiredAt,
    @Nullable String lastError,
    @Nullable Instant nextFireAt) {

  /**
   * Closed enum of the discriminator values surfaced on the wire. Kept as a separate constant set
   * so a forward-compat trigger type that the engine knows about but the UI does not is filtered
   * server-side (the server is the source of truth for the discriminated union).
   */
  public enum Type {
    cron,
    github,
    manual;
  }
}
