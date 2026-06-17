package io.adaptiq.titan.webhook;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Jakarta REST resource: {@code POST /api/v1/webhooks/github} — legacy receiver, deprecated and
 * redirected to the canonical {@code /api/v1/triggers/github} (issue #970, design/52).
 *
 * <p>This class is intentionally a pure redirect shim: no JSON parsing, no signature verification,
 * no DB writes. The legacy path used to authenticate via a single global secret and produced build
 * rows with {@code triggerMetaJson = NULL}, which broke the PR-status reporter (#26/#27) and the
 * build-detail header (#40). The canonical endpoint at {@link
 * io.adaptiq.titan.api.triggers.GithubWebhookApi} authenticates per-trigger and populates the
 * structured trigger metadata required by every downstream consumer.
 *
 * <p>HTTP 308 (Permanent Redirect, RFC 7538) is the correct status: unlike 301/302, 308 preserves
 * the HTTP method + body, so GitHub re-POSTs to the new path with the same payload + headers.
 * {@code Deprecation: true} and {@code Sunset: <date>} (RFC 8594) flag the deprecation window to
 * any well-behaved consumer.
 */
@Path("/api/v1/webhooks/github")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@PermitAll
public class WebhooksApi {

  /** Canonical replacement endpoint. */
  static final String CANONICAL_PATH = "/api/v1/triggers/github";

  /** RFC 8594 {@code Sunset} header value — date after which the legacy path may be removed. */
  static final String SUNSET_DATE = "Wed, 01 Jul 2026 00:00:00 GMT";

  @POST
  public Response receive() {
    // 308 preserves method + body — GitHub will re-POST the same delivery to the canonical path.
    // No body, no signature check, no DB interaction: every line of work belongs to the canonical
    // endpoint. This shim only exists so external consumers configured against the legacy URL
    // (and the docs/audit row still pointing at it) keep working through the sunset window.
    return Response.status(308)
        .header("Location", CANONICAL_PATH)
        .header("Deprecation", "true")
        .header("Sunset", SUNSET_DATE)
        .header("Link", "<" + CANONICAL_PATH + ">; rel=\"successor-version\"")
        .build();
  }
}
