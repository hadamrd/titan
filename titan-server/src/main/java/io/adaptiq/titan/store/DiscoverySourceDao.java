package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.DiscoverySourceRow;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindFields;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.discovery_sources} — configured discovery sources and the
 * mutable outcome of their last timed poll. Sources are keyed by their unique {@code name}.
 *
 * <p>Row POJOs ({@link DiscoverySourceRow}) are mapped field-by-field; JDBI's field mapper converts
 * snake_case columns to camelCase fields. {@code TitanStores} wires the {@code onDemand} proxy so
 * SQL failures surface as unchecked {@code TitanDataException}s.
 */
@RegisterFieldMapper(DiscoverySourceRow.class)
public interface DiscoverySourceDao {

  String COLS =
      "id, name, source_type, config_json, enabled, last_polled_at, last_status, last_error, created_at";

  // ──────────────────────────────────────────────
  // Queries
  // ──────────────────────────────────────────────
  @SqlQuery("SELECT " + COLS + " FROM titan.discovery_sources WHERE name = :name")
  @NonNull
  Optional<DiscoverySourceRow> findByName(@Bind("name") @NonNull String name);

  @SqlQuery("SELECT " + COLS + " FROM titan.discovery_sources WHERE enabled = TRUE ORDER BY name")
  @NonNull
  List<DiscoverySourceRow> listEnabled();

  // ──────────────────────────────────────────────
  // Mutations
  // ──────────────────────────────────────────────
  @SqlUpdate(
      "UPDATE titan.discovery_sources SET source_type = :sourceType, "
          + "config_json = :configJson, enabled = :enabled WHERE name = :name")
  int update(@BindFields DiscoverySourceRow row);

  @SqlUpdate(
      "INSERT INTO titan.discovery_sources (name, source_type, config_json, enabled) "
          + "VALUES (:name, :sourceType, :configJson, :enabled)")
  @GetGeneratedKeys
  long insert(@BindFields DiscoverySourceRow row);

  @SqlUpdate(
      "UPDATE titan.discovery_sources SET last_status = :status, last_error = :error, "
          + "last_polled_at = :at WHERE id = :id")
  void updatePollResult(
      @Bind("id") long id,
      @Bind("status") @Nullable String status,
      @Bind("error") @Nullable String error,
      @Bind("at") @Nullable Instant at);

  /**
   * Upsert a source by its unique {@code name} — update the existing row, or insert it if no row
   * with that name exists yet. Returns the row's id. Idempotent: re-running with the same
   * configuration converges the row to that state. Single-controller in v1, so the
   * update-then-insert is race-free (mirrors {@code JobTriggerDao.recordState}).
   */
  default long upsert(@NonNull DiscoverySourceRow row) {
    if (update(row) == 0) {
      return insert(row);
    }
    return findByName(row.name).orElseThrow().id;
  }
}
