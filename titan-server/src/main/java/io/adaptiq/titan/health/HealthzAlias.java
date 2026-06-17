package io.adaptiq.titan.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

/**
 * Backward-compatible {@code /healthz} alias.
 *
 * <p>The former Javalin boot wired {@code GET /healthz → {"status":"ok"}}. Any existing consumers
 * (Kubernetes probes, monitoring scripts) that hit {@code /healthz} must keep working without
 * change. This resource re-exposes the same JSON shape at the same URL.
 *
 * <p>The canonical health endpoints are {@code /q/health/live} and {@code /q/health/ready} — use
 * those for new integrations.
 */
@Path("/healthz")
@ApplicationScoped
public class HealthzAlias {

  @GET
  @Produces(MediaType.APPLICATION_JSON)
  public Map<String, String> healthz() {
    return Map.of("status", "ok");
  }
}
