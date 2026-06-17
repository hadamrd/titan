package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.rows.PulsarSourceRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject access for {@code titan.pulsar_sources} — registered Pulsar SCM node connections
 * (#1283). Mirrors {@link GithubInstallationDao}: generated-key insert + simple finders, with SQL
 * failures surfacing as {@link TitanDataException} via the {@link TitanStores} translating proxy.
 */
@RegisterFieldMapper(PulsarSourceRow.class)
public interface PulsarSourceDao {

  String COLS = "id, node_url, node_name, repo_count, last_polled_at, created_at, updated_at";

  @SqlQuery("SELECT " + COLS + " FROM titan.pulsar_sources ORDER BY id")
  @NonNull
  List<PulsarSourceRow> listAll();

  @SqlQuery("SELECT " + COLS + " FROM titan.pulsar_sources WHERE id = :id")
  @NonNull
  Optional<PulsarSourceRow> findById(@Bind("id") long id);

  @SqlQuery("SELECT " + COLS + " FROM titan.pulsar_sources WHERE node_url = :nodeUrl")
  @NonNull
  Optional<PulsarSourceRow> findByNodeUrl(@Bind("nodeUrl") @NonNull String nodeUrl);

  /**
   * Insert a fresh source row and return its generated id. Callers MUST check {@link
   * #findByNodeUrl} first; the unique constraint on {@code node_url} otherwise surfaces as a {@link
   * TitanDataException}.
   */
  @SqlUpdate(
      "INSERT INTO titan.pulsar_sources (node_url, node_name, repo_count, last_polled_at) "
          + "VALUES (:nodeUrl, :nodeName, :repoCount, CURRENT_TIMESTAMP)")
  @GetGeneratedKeys
  long insert(
      @Bind("nodeUrl") @NonNull String nodeUrl,
      @Bind("nodeName") @Nullable String nodeName,
      @Bind("repoCount") @Nullable Integer repoCount);

  /**
   * Record the result of a (re-)probe: stamp the observed {@code repo_count}, set {@code
   * last_polled_at} + {@code updated_at} to now. Returns the number of rows updated (0 = no such
   * id).
   */
  @SqlUpdate(
      "UPDATE titan.pulsar_sources SET "
          + " repo_count = :repoCount, "
          + " last_polled_at = CURRENT_TIMESTAMP, "
          + " updated_at = CURRENT_TIMESTAMP "
          + "WHERE id = :id")
  int updateSyncResult(@Bind("id") long id, @Bind("repoCount") int repoCount);
}
