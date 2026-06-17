package io.adaptiq.titan.store;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.store.rows.ArtifactRow;
import java.util.List;
import java.util.Optional;
import org.jdbi.v3.sqlobject.config.RegisterFieldMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * JDBI SqlObject read access for {@code titan.artifact} (design/41 32E-3) — the controller side,
 * which serves the build-page artifact browser. The write side is the worker's {@code
 * DbArtifactSink}.
 *
 * <p>Only {@code kind = 'ARTIFACT'} rows are exposed: a {@code STASH} is an internal, build-scoped,
 * intra-build file handoff — not something a user browses.
 */
@RegisterFieldMapper(ArtifactRow.class)
public interface ArtifactDao {

  String COLS =
      "id, build_id, node_id, kind, name, size_bytes, sha256, "
          + "storage, storage_ref, created_at";

  /** Every archived artifact of a build, in stable name order. */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.artifact "
          + "WHERE build_id = :buildId AND kind = 'ARTIFACT' ORDER BY name")
  @NonNull
  List<ArtifactRow> listArtifacts(@Bind("buildId") long buildId);

  /**
   * Count of archived ({@code kind = 'ARTIFACT'}) rows for a build — used by the paginated REST
   * list endpoint to populate the {@code total} field without loading every row.
   */
  @SqlQuery("SELECT COUNT(*) FROM titan.artifact WHERE build_id = :buildId AND kind = 'ARTIFACT'")
  int countByBuild(@Bind("buildId") long buildId);

  /**
   * One window of archived artifacts for a build, newest-id first ({@code ORDER BY id DESC}), with
   * the standard {@code LIMIT}/{@code OFFSET} cursor. {@code STASH} rows are excluded — same filter
   * as {@link #listArtifacts}. Used by the paginated REST list endpoint.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.artifact "
          + "WHERE build_id = :buildId AND kind = 'ARTIFACT' "
          + "ORDER BY id DESC LIMIT :limit OFFSET :offset")
  @NonNull
  List<ArtifactRow> findByBuildPaged(
      @Bind("buildId") long buildId, @Bind("offset") int offset, @Bind("limit") int limit);

  /**
   * One archived artifact of a build by its exact name. The name is looked up as data — never used
   * to build a filesystem path — so a download request cannot traverse outside the store.
   */
  @SqlQuery(
      "SELECT "
          + COLS
          + " FROM titan.artifact "
          + "WHERE build_id = :buildId AND kind = 'ARTIFACT' AND name = :name")
  @NonNull
  Optional<ArtifactRow> findArtifact(
      @Bind("buildId") long buildId, @Bind("name") @NonNull String name);

  /**
   * One archived artifact by its synthetic id — the lookup the download endpoint runs after parsing
   * {@code /api/v1/artifacts/{id}/download}. {@code STASH} rows are excluded: a stash is an
   * intra-build internal and is never addressable by the wire-format id.
   */
  @SqlQuery("SELECT " + COLS + " FROM titan.artifact " + "WHERE id = :id AND kind = 'ARTIFACT'")
  @NonNull
  Optional<ArtifactRow> findById(@Bind("id") long id);

  /**
   * The distinct {@code ArtifactStore} backend kinds a build's blobs live in — every {@code
   * storage} value across both {@code ARTIFACT} and {@code STASH} rows. The build reaper needs this
   * before it drops the {@code titan.builds} row: the {@code ON DELETE CASCADE} removes the
   * metadata rows, but the bytes in each backend must be reaped first, and only the rows record
   * which backend(s) a build used (a JCasC reconfigure can leave one build's blobs in {@code s3}
   * and a later build's in {@code fs}).
   */
  @SqlQuery("SELECT DISTINCT storage FROM titan.artifact WHERE build_id = :buildId")
  @NonNull
  List<String> storageKindsOf(@Bind("buildId") long buildId);

  /**
   * Drop every {@code titan.artifact} row of a build — used by artifact-only retention ({@code
   * LogRotator}'s {@code artifactNumToKeep}), which reaps a build's blobs but keeps its history.
   * Whole-build deletion does not call this: the {@code fk_artifact_build ON DELETE CASCADE}
   * removes the rows with the {@code titan.builds} row.
   *
   * @return the number of rows deleted
   */
  @SqlUpdate("DELETE FROM titan.artifact WHERE build_id = :buildId")
  int deleteRowsOf(@Bind("buildId") long buildId);
}
