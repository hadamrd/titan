package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * Response body for {@code GET /api/v1/system/info} — engine health dashboard payload (closes
 * #676). One round-trip aggregating the signals the SRE landing page needs:
 *
 * <ul>
 *   <li>{@code version} — Gradle project version (e.g. {@code "0.1.0"}); falls back to {@code
 *       "unknown"} when {@code META-INF/titan-build-info.properties} is absent.
 *   <li>{@code buildSha} — short git SHA written at compile time; {@code "unknown"} as above.
 *   <li>{@code dbStatus} — typed enum {@link DbStatus} from a 1-second DB ping. Never {@code null}.
 *   <li>{@code queueDepth} — count of {@code QUEUED} task rows across all queues (same source as
 *       {@code titan_queue_depth} gauges).
 *   <li>{@code workersOnline} — count of agents in {@code ONLINE} or {@code BUSY} states (DRAINING
 *       and OFFLINE excluded — "online" means "available to accept work or actively running it").
 *   <li>{@code serverTime} — wall-clock at request-handling time, useful for clock-skew checks.
 *   <li>{@code migrationStatus} — typed {@link MigrationStatus} surfacing the current applied
 *       Flyway version + the list of migrations bundled in the running image but not yet applied
 *       (closes #790: prevents silent migration drift between disk and rig).
 * </ul>
 *
 * <p>Discriminated-union typed — no {@code Map<String,Object>} on the wire. {@link DbStatus} is the
 * only string-discriminator; {@link MigrationStatus} is a typed record (closed shape).
 *
 * <p>Adversarial contract: when the DB is unreachable, the endpoint MUST return a 200 with {@code
 * dbStatus=DOWN} and best-effort zeros for the other counters — never a 500. This is enforced by
 * {@code SystemInfoApiIT.degradedDb_returnsDbStatusDown_withoutCrashing}. Likewise, {@code
 * migrationStatus} degrades to {@link MigrationStatus#UNKNOWN} when the history table is
 * unreachable — never null.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SystemInfoDto(
    String version,
    String buildSha,
    DbStatus dbStatus,
    int queueDepth,
    int workersOnline,
    Instant serverTime,
    MigrationStatus migrationStatus) {

  /** Two-state DB health discriminator. {@code DOWN} means the latest probe failed or timed out. */
  public enum DbStatus {
    UP,
    DOWN
  }

  /**
   * Flyway migration drift snapshot (closes #790). {@code current} is the highest version in {@code
   * titan.flyway_schema_history}; {@code pending} is the list of migration script versions found on
   * the classpath but NOT yet present in the history table — i.e. migrations the running image
   * carries but has not (or could not) apply. An empty {@code pending} means the deployed schema
   * matches what the running image expects.
   *
   * <p>{@code current=null} + empty {@code pending} = the probe could not read the history (DB
   * down, schema missing, permissions); the dashboard renders this as "unknown" rather than lying
   * with "in sync".
   */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record MigrationStatus(String current, List<String> pending) {

    /** Sentinel used when the probe fails for any reason. */
    public static final MigrationStatus UNKNOWN = new MigrationStatus(null, List.of());
  }
}
