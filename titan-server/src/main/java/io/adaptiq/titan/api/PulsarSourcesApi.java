package io.adaptiq.titan.api;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.dto.ProblemJson;
import io.adaptiq.titan.api.dto.PulsarSourceDto;
import io.adaptiq.titan.api.dto.RegisterPulsarSourceRequest;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.scm.pulsar.PulsarApiException;
import io.adaptiq.titan.scm.pulsar.PulsarClient;
import io.adaptiq.titan.scm.pulsar.PulsarClientFactory;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.PulsarSourceRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Jakarta REST resource: {@code /api/v1/pulsar/sources} — admin registration + listing of Pulsar
 * SCM node connections (#1283, convergence axis 2 / scm-depth). This is the backing endpoint for
 * the Titan UI's Pulsar Integrations card; landing it lets the card flip {@code available:true}.
 *
 * <ul>
 *   <li>{@code GET /api/v1/pulsar/sources} — list every registered source as a {@link
 *       PulsarSourceDto}.
 *   <li>{@code POST /api/v1/pulsar/sources} — register a node: validate the URL, reject a duplicate
 *       (409), probe reachability via {@link PulsarClient#listRepos()}, and on success persist with
 *       {@code repoCount = repos.size()} + {@code lastPolledAt = now} (201). An unreachable node is
 *       a typed 502 and is NOT persisted.
 *   <li>{@code POST /api/v1/pulsar/sources/{id}/sync} — re-probe a registered node and refresh its
 *       {@code repoCount} + {@code lastPolledAt}. Unknown id → 404; unreachable → 502.
 * </ul>
 *
 * <p><strong>RBAC:</strong> all endpoints require {@code ADMIN} — registering an SCM source is a
 * tenant-wide operation (mirrors {@link GithubAppApi}).
 *
 * <p><strong>Pulsar client wiring:</strong> mirrors {@link PulsarWebhookApi}: the production
 * constructor closes over {@code new PulsarClientFactory()::forNode}; a package-private test
 * constructor injects an explicit {@code Function<String, PulsarClient>} so the reachability probe
 * can be exercised without a live node. Each source carries its own {@code nodeUrl}, so the client
 * is built per-request via {@link PulsarClientFactory#forNode(String)}.
 */
@Path("/api/v1/pulsar/sources")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class PulsarSourcesApi {

  private static final Logger LOGGER = Logger.getLogger(PulsarSourcesApi.class.getName());
  private static final String PROBLEM_CONTENT_TYPE = "application/problem+json";

  private final TitanStores stores;
  private final Function<String, PulsarClient> clientForNode;

  /**
   * Production constructor — builds a {@link PulsarClient} per node via {@link
   * PulsarClientFactory}.
   */
  @Inject
  public PulsarSourcesApi(TitanStores stores) {
    this(stores, new PulsarClientFactory()::forNode);
  }

  /** Test-only constructor — explicit per-node client resolver, no network. */
  PulsarSourcesApi(
      @NonNull TitanStores stores, @NonNull Function<String, PulsarClient> clientForNode) {
    this.stores = stores;
    this.clientForNode = clientForNode;
  }

  // ── GET /api/v1/pulsar/sources ─────────────────────────────────────────────

  @GET
  @RolesAllowed(Roles.ADMIN)
  public List<PulsarSourceDto> list() {
    return stores.pulsarSources().listAll().stream().map(PulsarSourceDto::from).toList();
  }

  // ── POST /api/v1/pulsar/sources ────────────────────────────────────────────

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response register(RegisterPulsarSourceRequest req) {
    if (req == null || req.nodeUrl() == null || req.nodeUrl().isBlank()) {
      throw new ApiBadRequestException("nodeUrl is required");
    }
    String nodeUrl = req.nodeUrl().trim();
    if (!isWellFormedHttpUrl(nodeUrl)) {
      throw new ApiBadRequestException("nodeUrl must be a well-formed http(s) URL: " + nodeUrl);
    }
    String nodeName = blankToNull(req.nodeName());

    // Reject a duplicate up front (409) — the unique constraint would otherwise surface as a 500.
    if (stores.pulsarSources().findByNodeUrl(nodeUrl).isPresent()) {
      return problem(
          409, "pulsar-source-exists", "a Pulsar source is already registered for " + nodeUrl);
    }

    // Probe reachability BEFORE persisting — never register a node we cannot reach. A node 5xx /
    // socket failure surfaces as a typed PulsarApiException → 502, not a leaked 500/stacktrace.
    int repoCount;
    try {
      repoCount = clientForNode.apply(nodeUrl).listRepos().size();
    } catch (PulsarApiException e) {
      LOGGER.log(
          Level.INFO,
          "[pulsar] registration probe failed for {0}: {1}",
          new Object[] {nodeUrl, e.getMessage()});
      return unreachable(nodeUrl, e);
    }

    long id = stores.pulsarSources().insert(nodeUrl, nodeName, repoCount);
    PulsarSourceRow row =
        stores
            .pulsarSources()
            .findById(id)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "pulsar source " + id + " vanished immediately after insert"));
    return Response.status(201).entity(PulsarSourceDto.from(row)).build();
  }

  // ── POST /api/v1/pulsar/sources/{id}/sync ──────────────────────────────────

  @POST
  @Path("/{id}/sync")
  @RolesAllowed(Roles.ADMIN)
  @RequiresRole(role = Authz.TitanRole.ADMIN, kind = ScopeKind.ORG, scopeId = "global")
  public Response sync(@PathParam("id") String idStr) {
    long id = JobsApi.parseLong(idStr, "id");
    PulsarSourceRow row =
        stores
            .pulsarSources()
            .findById(id)
            .orElseThrow(() -> new ApiNotFoundException("pulsar source " + id + " not found"));

    int repoCount;
    try {
      repoCount = clientForNode.apply(row.nodeUrl).listRepos().size();
    } catch (PulsarApiException e) {
      LOGGER.log(
          Level.INFO,
          "[pulsar] sync probe failed for source {0} ({1}): {2}",
          new Object[] {id, row.nodeUrl, e.getMessage()});
      return unreachable(row.nodeUrl, e);
    }

    stores.pulsarSources().updateSyncResult(id, repoCount);
    PulsarSourceRow refreshed =
        stores
            .pulsarSources()
            .findById(id)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "pulsar source " + id + " vanished during sync refresh"));
    return Response.ok(PulsarSourceDto.from(refreshed)).build();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /**
   * A well-formed absolute http(s) URL with a host. Rejects relative URLs, non-http schemes, and
   * syntactically-invalid strings — the input is the operator's, so a bad URL is a 400, not a 500.
   */
  static boolean isWellFormedHttpUrl(@NonNull String raw) {
    try {
      URI uri = new URI(raw);
      String scheme = uri.getScheme();
      if (scheme == null) {
        return false;
      }
      String s = scheme.toLowerCase(Locale.ROOT);
      return (s.equals("http") || s.equals("https"))
          && uri.getHost() != null
          && !uri.getHost().isBlank();
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static String blankToNull(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  /**
   * Map an unreachable-node {@link PulsarApiException} to a typed gateway error: 502 for a
   * transport failure or upstream 5xx, 502 for any non-2xx HTTP. The Pulsar node is an upstream
   * dependency, so its failure is a Bad Gateway, never a Titan 500.
   */
  @NonNull
  private Response unreachable(@NonNull String nodeUrl, @NonNull PulsarApiException e) {
    return problem(
        502,
        "pulsar-node-unreachable",
        "Pulsar node " + nodeUrl + " did not respond successfully: " + e.getMessage());
  }

  @NonNull
  private Response problem(int status, @NonNull String slug, @NonNull String detail) {
    Optional<ProblemJson> body =
        Optional.of(new ProblemJson("about:blank", slug, status, detail, null));
    return Response.status(status).type(PROBLEM_CONTENT_TYPE).entity(body.get()).build();
  }
}
