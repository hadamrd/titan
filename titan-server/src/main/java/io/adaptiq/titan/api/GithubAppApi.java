package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.DiscoveredPipelineDto;
import io.adaptiq.titan.api.dto.GithubAppDto;
import io.adaptiq.titan.api.dto.GithubInstallationDto;
import io.adaptiq.titan.api.dto.GithubRepoDto;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.scm.github.GithubApiException;
import io.adaptiq.titan.scm.github.GithubAppService;
import io.adaptiq.titan.scm.github.GithubRepoScanner;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.GithubAppRow;
import io.adaptiq.titan.store.rows.GithubInstallationRow;
import io.adaptiq.titan.store.rows.GithubPipelineDiscoveredRow;
import io.adaptiq.titan.store.rows.GithubRepositoryRow;
import io.adaptiq.titan.store.rows.JobRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;

/**
 * Jakarta REST resource: {@code /api/v1/github-app} — GitHub App registration + installation
 * management (#832, design/63).
 *
 * <ul>
 *   <li>{@code GET /api/v1/github-app} — returns the registered App (or null) so the UI can branch
 *       between "create App" and "install on org" flows.
 *   <li>{@code POST /api/v1/github-app/manifest-callback?code=&lt;temp&gt;} — receives GitHub's
 *       redirect from the App Manifest flow, exchanges the temp code for the App's id+PEM+secret,
 *       persists the singleton row. Replayable in place.
 *   <li>{@code GET /api/v1/github-app/installations} — list persisted installations.
 *   <li>{@code POST /api/v1/github-app/installations/{id}/sync} — refresh repos for one install.
 * </ul>
 *
 * <p><strong>Security:</strong> every response carries {@link GithubAppDto} / {@link
 * GithubInstallationDto}. The PEM, webhook secret, and sealed envelope bytes are NEVER projected
 * onto the wire. {@link io.adaptiq.titan.api.GithubAppApiTest#manifestCallback_*} locks this
 * contract.
 *
 * <p><strong>RBAC:</strong> all endpoints require {@code ADMIN} — registering / managing the GitHub
 * App is a tenant-wide operation; a per-job role would not make sense here.
 */
@Path("/api/v1/github-app")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class GithubAppApi {

  private final GithubAppService service;
  private final GithubRepoScanner scanner;
  private final TitanStores stores;

  @Inject
  GithubAppApi(GithubAppService service, GithubRepoScanner scanner, TitanStores stores) {
    this.service = service;
    this.scanner = scanner;
    this.stores = stores;
  }

  /**
   * Enrich an installation row with its repos + each repo's discovered pipelines + the enabled-job
   * lookup so the UI can render the full nested card without N+1 fetches (#876).
   */
  private GithubInstallationDto enrich(GithubInstallationRow inst) {
    List<GithubRepositoryRow> repoRows = stores.githubRepositories().listByInstall(inst.installId);
    // One job query per install — find every Job whose (installId, repoId) lives under it. The
    // matcher per-pipeline is then by-filename inside config_json. This is one DAO call per install
    // (not per repo) — fine for the typical install size (≤100 repos).
    java.util.Map<Long, List<JobRow>> jobsByRepoId = new java.util.HashMap<>();
    for (GithubRepositoryRow repo : repoRows) {
      jobsByRepoId.put(repo.repoId, stores.jobs().findByGithubRepo(inst.installId, repo.repoId));
    }
    List<GithubRepoDto> repoDtos = new java.util.ArrayList<>(repoRows.size());
    for (GithubRepositoryRow repo : repoRows) {
      // UI invariant (#887, design 65): the canonical pipeline list per repo is the
      // default-branch parse. Per-branch rows exist to feed per-push build dispatch but would
      // otherwise pollute the user-facing view with feature-branch-only YAMLs and stale
      // deleted-branch entries.
      String displayBranch = repo.defaultBranch != null ? repo.defaultBranch : "main";
      List<GithubPipelineDiscoveredRow> pipelineRows =
          stores.githubPipelinesDiscovered().listByRepoAndBranch(repo.repoId, displayBranch);
      List<JobRow> jobsForRepo = jobsByRepoId.getOrDefault(repo.repoId, List.of());
      List<DiscoveredPipelineDto> pipelineDtos = new java.util.ArrayList<>(pipelineRows.size());
      for (GithubPipelineDiscoveredRow pr : pipelineRows) {
        JobRow match = jobMatching(jobsForRepo, repo.owner, repo.name, pr.filename);
        pipelineDtos.add(
            DiscoveredPipelineDto.from(pr, match != null, match != null ? match.id : null));
      }
      repoDtos.add(GithubRepoDto.from(repo, pipelineDtos));
    }
    return GithubInstallationDto.from(inst, repoDtos);
  }

  /**
   * Match a discovered pipeline to a Job row. A Job has a unique {@code full_name} like {@code
   * <owner>/<repo>/<pipelineShortName>} — derive the short name from the filename (strip directory
   * + extension) and look up. Belt-and-suspenders: also accept a config_json that substring-matches
   * the filename, so jobs created by the manual API still match.
   */
  private static JobRow jobMatching(List<JobRow> jobs, String owner, String name, String filename) {
    String shortName = filename;
    int slash = shortName.lastIndexOf('/');
    if (slash >= 0) shortName = shortName.substring(slash + 1);
    if (shortName.endsWith(".yml")) shortName = shortName.substring(0, shortName.length() - 4);
    else if (shortName.endsWith(".yaml"))
      shortName = shortName.substring(0, shortName.length() - 5);
    String wantFullName = owner + "/" + name + "/" + shortName;
    for (JobRow j : jobs) {
      if (j.fullName != null && j.fullName.equals(wantFullName)) return j;
      if (j.configJson != null && j.configJson.contains(filename)) return j;
    }
    return null;
  }

  // ── GET /api/v1/github-app ────────────────────────────────────────────────

  /**
   * Return the registered App, or 204 (No Content) if nothing has been registered yet. The UI uses
   * the empty case to render the "Create Titan GitHub App" CTA; the populated case to render the
   * installations list.
   */
  @GET
  @RolesAllowed(Roles.ADMIN)
  public Response getApp() {
    Optional<GithubAppRow> row = service.findApp();
    if (row.isEmpty()) {
      return Response.noContent().build();
    }
    return Response.ok(GithubAppDto.from(row.get())).build();
  }

  // ── POST /api/v1/github-app/manifest-callback?code=... ────────────────────

  @POST
  @Path("/manifest-callback")
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response manifestCallback(@QueryParam("code") String code) {
    if (code == null || code.isBlank()) {
      throw new ApiBadRequestException("query parameter 'code' is required");
    }
    try {
      GithubAppRow row = service.handleManifestCallback(code);
      return Response.status(201).entity(GithubAppDto.from(row)).build();
    } catch (GithubApiException e) {
      // GitHub returns 422 for invalid / already-redeemed codes. Surface as 400 to the UI —
      // the failure is the user's input (a stale or re-used redirect), not a Titan bug.
      if (e.status() == 422 || e.status() == 404) {
        throw new ApiBadRequestException(
            "GitHub rejected the manifest code (invalid or already redeemed)");
      }
      throw e;
    }
  }

  // ── GET /api/v1/github-app/installations ──────────────────────────────────

  @GET
  @Path("/installations")
  @RolesAllowed(Roles.ADMIN)
  public List<GithubInstallationDto> listInstallations() {
    return service.listInstallations().stream().map(this::enrich).toList();
  }

  // ── POST /api/v1/github-app/installations/sync ────────────────────────────

  /**
   * Refresh the installations table from GitHub — pulls the live {@code GET /app/installations}
   * list and upserts. Used by the UI's "Refresh installations" button (and eventually on {@code
   * installation} webhook events).
   */
  @POST
  @Path("/installations/sync")
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public List<GithubInstallationDto> syncInstallations() {
    return service.syncInstallations().stream().map(this::enrich).toList();
  }

  // ── POST /api/v1/github-app/installations/{installId}/sync ────────────────

  /** Refresh the {@code titan.github_repositories} table for one install. */
  @POST
  @Path("/installations/{installId}/sync")
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response syncRepositories(@PathParam("installId") String installIdStr) {
    long installId = JobsApi.parseLong(installIdStr, "installId");
    try {
      // Child B (#833): the sync endpoint now drives the full pipeline-discovery scan, not
      // just the repo list refresh. scanInstall syncs the repos first, then walks each one's
      // `.titan/pipelines/` directory and persists the discovered metadata.
      GithubRepoScanner.ScanReport report = scanner.scanInstall(installId);
      return Response.ok(new SyncResult(installId, report.reposScanned(), report.pipelinesFound()))
          .build();
    } catch (GithubApiException e) {
      if (e.status() == 404 || e.status() == 401) {
        throw new ApiNotFoundException("installation " + installId + " not found");
      }
      throw e;
    }
  }

  /**
   * Response body for the per-install sync endpoint. {@code pipelineCount} is the number of {@code
   * .titan/pipelines/*.yml} files discovered across every repo in the installation (#833).
   */
  public record SyncResult(long installId, int repositoryCount, int pipelineCount) {}
}
