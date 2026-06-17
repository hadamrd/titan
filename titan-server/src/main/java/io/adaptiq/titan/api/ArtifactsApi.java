package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.ArtifactDto;
import io.adaptiq.titan.api.dto.ArtifactsPage;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.store.TitanStores;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;

/**
 * Jakarta REST resource: {@code GET /api/v1/builds/{buildId}/artifacts} — paginated browser for the
 * archived artifacts of a single build (closes #297, backend half).
 *
 * <p><strong>Wire-format contract.</strong> Every item is an {@link ArtifactDto}: id, name,
 * sizeBytes, sha256, uploadedAt, downloadUrl. The internal storage-backend locators ({@code
 * storage}, {@code storage_ref}) and the executor node id are <em>never</em> projected — they are
 * server-side implementation detail. The {@code kind} discriminator is filtered out too: only
 * {@code ARTIFACT} rows reach the wire ({@code STASH} entries are intra-build internals).
 *
 * <p><strong>Pagination.</strong> {@code offset} clamped to a non-negative integer; {@code limit}
 * capped server-side at 500 (default 200). The companion {@code total} field reports the full count
 * for the build so the UI can render the pagination footer without iterating the windows.
 *
 * <p><strong>Download URL.</strong> Each item exposes {@code /api/v1/artifacts/{id}/download} as
 * its {@code downloadUrl}. That endpoint itself is a deliberate follow-up — the URL shape is frozen
 * now so the UI can wire its anchors against a stable contract.
 *
 * <p><strong>RBAC.</strong> {@code READ_JOB}, {@code TRIGGER_BUILD}, or {@code ADMIN}. Build
 * existence is validated through {@link BuildService} — same pattern as {@link BuildDetailApi} — so
 * a missing build returns 404 instead of an empty page.
 */
@Path("/api/v1/builds")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class ArtifactsApi {

  /** Server-side hard cap on a single page. */
  static final int MAX_LIMIT = 500;

  /** Default page size when the client omits {@code limit}. */
  static final int DEFAULT_LIMIT = 200;

  private final TitanStores stores;
  private final BuildService builds;

  ArtifactsApi(TitanStores stores, BuildService builds) {
    this.stores = stores;
    this.builds = builds;
  }

  // ── GET /api/v1/builds/{buildId}/artifacts ────────────────────────────────

  @GET
  @Path("{buildId}/artifacts")
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public ArtifactsPage listArtifacts(
      @PathParam("buildId") String buildIdStr,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("200") int limit) {
    long buildId = JobsApi.parseLong(buildIdStr, "buildId");
    builds
        .findById(buildId)
        .orElseThrow(() -> new ApiNotFoundException("build " + buildId + " not found"));

    int cappedLimit = Math.min(Math.max(limit, 0), MAX_LIMIT);
    int safeOffset = Math.max(offset, 0);

    int total = stores.artifacts().countByBuild(buildId);
    List<ArtifactDto> items =
        stores.artifacts().findByBuildPaged(buildId, safeOffset, cappedLimit).stream()
            .map(ArtifactDto::from)
            .toList();
    return new ArtifactsPage(items, total);
  }
}
