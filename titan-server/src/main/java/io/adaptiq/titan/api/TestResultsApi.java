package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.TestResultsPage;
import io.adaptiq.titan.api.dto.TestRowDto;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.build.BuildService;
import io.adaptiq.titan.store.TestResultDao.TestSummary;
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
 * Jakarta REST resource: {@code GET /api/v1/builds/{buildId}/tests} — paginated browser for the
 * parsed JUnit test cases of a single build (closes #296, backend half).
 *
 * <p><strong>Wire-format contract.</strong> Every item is a {@link TestRowDto}: id, suite,
 * className, name, status, durationMs, and — only on {@code FAILED} rows — failureMessage. The
 * internal columns ({@code nodeId}, {@code buildId}, {@code createdAt}) are <em>never</em>
 * projected; they are server-side implementation detail.
 *
 * <p>The response is wrapped in a {@link TestResultsPage} that also carries a build-wide summary
 * (passed / failed / skipped counts) — the counts span the whole build, not just the requested
 * window, so the UI's top-of-panel counters stay correct regardless of pagination.
 *
 * <p><strong>Pagination.</strong> {@code offset} clamped to a non-negative integer; {@code limit}
 * capped server-side at 500 (default 200). The companion {@code total} field reports the full count
 * for the build so the UI can render the pagination footer without iterating windows.
 *
 * <p><strong>RBAC.</strong> {@code READ_JOB}, {@code TRIGGER_BUILD}, or {@code ADMIN}. Build
 * existence is validated through {@link BuildService} — same pattern as {@link ArtifactsApi} — so a
 * missing build returns 404 instead of an empty page.
 */
@Path("/api/v1/builds")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class TestResultsApi {

  /** Server-side hard cap on a single page. */
  static final int MAX_LIMIT = 500;

  /** Default page size when the client omits {@code limit}. */
  static final int DEFAULT_LIMIT = 200;

  private final TitanStores stores;
  private final BuildService builds;

  TestResultsApi(TitanStores stores, BuildService builds) {
    this.stores = stores;
    this.builds = builds;
  }

  // ── GET /api/v1/builds/{buildId}/tests ────────────────────────────────────

  @GET
  @Path("{buildId}/tests")
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  public TestResultsPage listTests(
      @PathParam("buildId") String buildIdStr,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("200") int limit) {
    long buildId = JobsApi.parseLong(buildIdStr, "buildId");
    builds
        .findById(buildId)
        .orElseThrow(() -> new ApiNotFoundException("build " + buildId + " not found"));

    int cappedLimit = Math.min(Math.max(limit, 0), MAX_LIMIT);
    int safeOffset = Math.max(offset, 0);

    int total = stores.testResults().countByBuild(buildId);
    TestSummary summary = stores.testResults().summaryByBuild(buildId);
    List<TestRowDto> items =
        stores.testResults().findByBuildPaged(buildId, safeOffset, cappedLimit).stream()
            .map(TestRowDto::from)
            .toList();
    return new TestResultsPage(items, total, summary);
  }
}
