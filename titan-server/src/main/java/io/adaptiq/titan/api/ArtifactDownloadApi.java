package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.SignedDownloadDto;
import io.adaptiq.titan.artifact.ArtifactDownloadSigner;
import io.adaptiq.titan.artifact.ArtifactStoreResolver;
import io.adaptiq.titan.auth.Authz;
import io.adaptiq.titan.auth.RequiresRole;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.flow.artifact.ArtifactStore;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.ArtifactRow;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Jakarta REST resource for the artifact-bytes surface (closes #393, browser-download fix #849).
 *
 * <p><strong>Two endpoints, two auth models.</strong>
 *
 * <ul>
 *   <li>{@code POST /api/v1/artifacts/{id}/sign-download} — bearer-gated ({@code @RolesAllowed}).
 *       The SPA POSTs here, server returns a short-lived HMAC-signed URL pointing at the streaming
 *       endpoint below. This is the only path the UI ever takes for browser-native Preview / Get
 *       actions, because an {@code <iframe src=>} or {@code <a href=>} navigation CANNOT carry the
 *       SPA's bearer header.
 *   <li>{@code GET /api/v1/artifacts/{id}/download} — {@code @PermitAll} at the framework level,
 *       but enforces auth INTERNALLY: either a valid OIDC bearer with one of {READ_JOB,
 *       TRIGGER_BUILD, ADMIN} (back-compat path: existing CLI clients keep working), OR a valid
 *       {@code ?token=} HMAC bound to this artifact id. We can't keep the {@code @RolesAllowed} at
 *       the framework level because Quarkus rejects the request before our token check runs.
 * </ul>
 *
 * <p><strong>Lookup is by synthetic id.</strong> Only {@code kind = 'ARTIFACT'} rows are
 * addressable — STASH entries are intra-build internal.
 *
 * <p><strong>Backend dispatch is typed.</strong> Row's {@code storage} short discriminator (never a
 * URL) routes through {@link ArtifactStoreResolver#forKind(String)}.
 *
 * <p><strong>Streamed, never buffered.</strong> The blob's {@link InputStream} pipes to a {@link
 * StreamingOutput}; {@code Content-Length} is the row's exact {@code size_bytes}.
 */
@Path("/api/v1/artifacts")
@ApplicationScoped
public class ArtifactDownloadApi {

  private static final Map<String, String> EXTENSION_MEDIA_TYPES =
      Map.ofEntries(
          Map.entry("txt", MediaType.TEXT_PLAIN),
          Map.entry("log", MediaType.TEXT_PLAIN),
          Map.entry("json", MediaType.APPLICATION_JSON),
          Map.entry("xml", MediaType.APPLICATION_XML),
          Map.entry("html", MediaType.TEXT_HTML),
          Map.entry("htm", MediaType.TEXT_HTML),
          Map.entry("css", "text/css"),
          Map.entry("js", "application/javascript"),
          Map.entry("yml", "application/yaml"),
          Map.entry("yaml", "application/yaml"),
          Map.entry("pdf", "application/pdf"),
          Map.entry("zip", "application/zip"),
          Map.entry("tar", "application/x-tar"),
          Map.entry("gz", "application/gzip"),
          Map.entry("jar", "application/java-archive"));

  /** Roles whose bearer also authorises bypass of the HMAC token. */
  private static final java.util.Set<String> DOWNLOAD_ROLES =
      java.util.Set.of(Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN);

  private final TitanStores stores;
  private final ArtifactStoreResolver resolver;
  private final ArtifactDownloadSigner signer;
  private final SecurityIdentity identity;

  ArtifactDownloadApi(
      TitanStores stores,
      ArtifactStoreResolver resolver,
      ArtifactDownloadSigner signer,
      SecurityIdentity identity) {
    this.stores = stores;
    this.resolver = resolver;
    this.signer = signer;
    this.identity = identity;
  }

  /**
   * Package-private factory for stream-path integration tests (e.g. {@code ArtifactDownloadApiIT}).
   * The returned instance bypasses the auth gate — callers MUST be test fixtures that build the API
   * by hand, never the CDI container. The {@code @ApplicationScoped} production wiring always goes
   * through the four-arg constructor.
   */
  static ArtifactDownloadApi forStreamingTestsOnly(
      TitanStores stores, ArtifactStoreResolver resolver) {
    return new ArtifactDownloadApi(stores, resolver, null, null);
  }

  // ── POST /api/v1/artifacts/{id}/sign-download ────────────────────────────

  /**
   * Mint a short-lived HMAC-signed download URL for the named artifact (closes #849).
   *
   * <p>Same RBAC as {@code GET …/download}: a caller who can stream the bytes via bearer can also
   * mint a token. The browser then navigates the returned URL directly with no header gymnastics —
   * the URL's {@code ?token=} carries the auth. TTL is {@link ArtifactDownloadSigner#DEFAULT_TTL}
   * (5 minutes).
   *
   * <p>404 if the artifact id doesn't exist OR the row is a STASH — same observable shape as the
   * legacy bearer path, so an unauthorised probe cannot distinguish.
   */
  @POST
  @Path("{id}/sign-download")
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.READ_JOB, Roles.TRIGGER_BUILD, Roles.ADMIN})
  @RequiresRole(role = Authz.TitanRole.VIEWER, kind = ScopeKind.ORG, scopeId = "global")
  public SignedDownloadDto signDownload(@PathParam("id") String idStr) {
    long id = JobsApi.parseLong(idStr, "id");
    // Confirm the row exists (and is an ARTIFACT, not a STASH) before minting a token. We don't
    // want /sign-download to mint URLs for ids that the streaming endpoint would 404 — that's a
    // confusing UX and a small information-leak surface.
    stores
        .artifacts()
        .findById(id)
        .orElseThrow(() -> new ApiNotFoundException("artifact " + id + " not found"));

    ArtifactDownloadSigner.SignedDownloadToken signed = signer.sign(id);
    String url = "/api/v1/artifacts/" + id + "/download?token=" + signed.token();
    return new SignedDownloadDto(url, signed.expiresAt());
  }

  // ── GET /api/v1/artifacts/{id}/download ───────────────────────────────────

  /**
   * Stream the bytes of an archived artifact.
   *
   * <p>Auth: the framework annotation is {@code @PermitAll} so the request reaches us before
   * Quarkus' role gate fires; we enforce the policy in-method. Accepts EITHER:
   *
   * <ul>
   *   <li>{@code Authorization: Bearer <JWT>} with one of {READ_JOB, TRIGGER_BUILD, ADMIN} — the
   *       legacy / CLI path, kept for back-compat.
   *   <li>{@code ?token=<HMAC>} bound to this exact artifact id, unexpired — the browser-native
   *       Preview / Get path that the SPA always uses.
   * </ul>
   *
   * Neither present → 401. Missing role on a bearer → 403 (same as the legacy behaviour).
   */
  @GET
  @Path("{id}/download")
  @PermitAll
  public Response download(@PathParam("id") String idStr, @QueryParam("token") String token) {
    long id = JobsApi.parseLong(idStr, "id");

    authorise(id, token);

    ArtifactRow row =
        stores
            .artifacts()
            .findById(id)
            .orElseThrow(() -> new ApiNotFoundException("artifact " + id + " not found"));

    ArtifactStore store;
    try {
      store = resolver.forKind(row.storage);
    } catch (IllegalArgumentException e) {
      throw new WebApplicationException(
          "artifact " + id + " backend '" + row.storage + "' is not available",
          Response.Status.SERVICE_UNAVAILABLE);
    }

    Optional<InputStream> opened;
    try {
      opened = store.open(row.storageRef);
    } catch (IOException e) {
      throw new WebApplicationException(
          "failed to open artifact " + id, e, Response.Status.BAD_GATEWAY);
    }
    if (opened.isEmpty()) {
      throw new ApiNotFoundException("artifact " + id + " bytes not found in backend");
    }
    InputStream body = opened.get();

    StreamingOutput stream =
        out -> {
          try (InputStream in = body) {
            in.transferTo(out);
          }
        };

    return Response.ok(stream, contentTypeFor(row.name))
        .header(HttpHeaders.CONTENT_LENGTH, row.sizeBytes)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"" + sanitizeFilename(basename(row.name)) + "\"")
        .build();
  }

  // ── auth helpers ──────────────────────────────────────────────────────────

  /**
   * Enforce the two-mode auth policy described on {@link #download}. Throws {@link
   * NotAuthorizedException} (401) when neither mode authorises the request, {@link
   * jakarta.ws.rs.ForbiddenException} (403) when a bearer is present but missing the required role.
   * The {@link #forStreamingTestsOnly} factory wires null collaborators — in that mode we fall
   * through to "allow" because those test fixtures don't go through the HTTP layer at all.
   */
  private void authorise(long artifactId, String token) {
    if (signer == null || identity == null) {
      // Stream-only test path — see #forStreamingTestsOnly. Never reached in production.
      return;
    }
    // Mode 1: HMAC token. Constant-time, artifact-bound — cross-artifact replay rejected by the
    // signer itself.
    if (token != null && !token.isEmpty() && signer.verify(artifactId, token)) {
      return;
    }
    // Mode 2: bearer + role.
    if (!identity.isAnonymous()) {
      for (String role : DOWNLOAD_ROLES) {
        if (identity.hasRole(role)) {
          return;
        }
      }
      throw new jakarta.ws.rs.ForbiddenException(
          "caller is authenticated but lacks any of " + DOWNLOAD_ROLES);
    }
    throw new NotAuthorizedException(
        "artifact download requires a valid bearer or a signed ?token=", "Bearer");
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  static String basename(String name) {
    if (name == null || name.isEmpty()) {
      return "artifact";
    }
    int slash = name.lastIndexOf('/');
    String tail = slash >= 0 ? name.substring(slash + 1) : name;
    return tail.isEmpty() ? "artifact" : tail;
  }

  static String sanitizeFilename(String tail) {
    StringBuilder sb = new StringBuilder(tail.length());
    for (int i = 0; i < tail.length(); i++) {
      char c = tail.charAt(i);
      if (c == '"' || c == '\\' || c == '\r' || c == '\n') {
        sb.append('_');
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  static String contentTypeFor(String name) {
    if (name == null) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
    String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
    return EXTENSION_MEDIA_TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM);
  }
}
