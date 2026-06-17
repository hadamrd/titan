package io.adaptiq.titan.api;

import io.adaptiq.titan.api.dto.RbacAuditEventDto;
import io.adaptiq.titan.api.dto.RbacAuditPage;
import io.adaptiq.titan.auth.RbacDecision;
import io.adaptiq.titan.auth.Roles;
import io.adaptiq.titan.auth.ScopeKind;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.RbacAuditRow;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Jakarta REST resource: {@code /api/v1/rbac-audit} — the operator-facing read surface over {@code
 * titan.rbac_audit}, the typed allow/deny trail for every {@code @RequiresRole}-gated call (closes
 * #1167, reads the table written by #1131/#1149).
 *
 * <p>Distinct from {@link AuditApi} ({@code /api/v1/audit} over {@code audit_log}): there, RBAC
 * verdicts are buried inside an opaque {@code detailsJson} blob. Here {@code verdict} is a
 * first-class, filterable, closed-union ({@link RbacDecision}) column — the admin can answer "who
 * was <b>denied</b> what, and why" during an incident.
 *
 * <ul>
 *   <li>{@code GET /api/v1/rbac-audit} — paginated, filterable by {@code actor} (ILIKE substring on
 *       the user id), {@code verdict} (repeatable, validated against {@link RbacDecision}), {@code
 *       scopeKind} (validated against {@link ScopeKind}), and {@code since} (ISO-8601 lower bound).
 * </ul>
 *
 * <p>Gated identically to {@link AuditApi}: {@link Roles#READ_AUDIT} grants view-only access;
 * {@link Roles#ADMIN} works as the super-role. A non-audit role gets {@code 403} from the
 * {@code @RolesAllowed} interceptor before this handler runs.
 *
 * <p>Filter hygiene (mirrors {@code AuditApi}): every filter token is validated against the
 * server-side enum so a caller cannot fish for arbitrary values; an invalid {@code verdict} /
 * {@code scopeKind} / {@code since} surfaces as HTTP 400 (not a silent empty result, never a 500).
 * An empty / all-blank filter degrades to "no filter", never "match nothing" — a stale URL with a
 * half-typed filter must not appear to lose the whole trail. Selecting <b>both</b> verdicts is
 * equivalent to selecting neither (the unfiltered feed).
 */
@Path("/api/v1/rbac-audit")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
@RolesAllowed({Roles.READ_AUDIT, Roles.ADMIN})
public class RbacAuditApi {

  private static final int MAX_LIMIT = 200;
  private static final int MAX_ACTOR_LEN = 200;

  private final TitanStores stores;

  RbacAuditApi(TitanStores stores) {
    this.stores = stores;
  }

  @GET
  public RbacAuditPage list(
      @QueryParam("actor") String actor,
      @QueryParam("verdict") List<String> verdictRaw,
      @QueryParam("scopeKind") String scopeKind,
      @QueryParam("since") String sinceStr,
      @QueryParam("offset") @DefaultValue("0") int offset,
      @QueryParam("limit") @DefaultValue("50") int limit) {
    int cappedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
    int safeOffset = Math.max(offset, 0);

    String actorFilter = nullIfBlank(actor);
    if (actorFilter != null && actorFilter.length() > MAX_ACTOR_LEN) {
      throw new ApiBadRequestException(
          "actor length " + actorFilter.length() + " exceeds cap " + MAX_ACTOR_LEN);
    }

    String decisionFilter = normaliseVerdict(verdictRaw);
    String scopeKindFilter = validateScopeKind(nullIfBlank(scopeKind));
    Instant since = parseSince(sinceStr);

    List<RbacAuditRow> rows =
        stores
            .rbacAudit()
            .findFiltered(
                actorFilter, decisionFilter, scopeKindFilter, since, cappedLimit, safeOffset);
    long total =
        stores.rbacAudit().countFiltered(actorFilter, decisionFilter, scopeKindFilter, since);
    List<RbacAuditEventDto> page = rows.stream().map(RbacAuditEventDto::from).toList();
    return new RbacAuditPage(page, total, safeOffset, cappedLimit);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private static String nullIfBlank(String s) {
    return (s == null || s.isBlank()) ? null : s.trim();
  }

  /**
   * De-dupe the repeated {@code verdict=} params, drop blanks, validate each against {@link
   * RbacDecision}, and collapse to the single {@code decision} the DAO filters on.
   *
   * <ul>
   *   <li>empty / all-blank → {@code null} (no filter — the recent feed).
   *   <li>exactly one distinct verdict → that verdict's {@link RbacDecision#name()}.
   *   <li>both verdicts → {@code null} (selecting ALLOW+DENY is the whole feed; no point in a
   *       two-element {@code IN} that matches every row).
   *   <li>any unrecognised token → HTTP 400 (a caller cannot fish for {@code verdict=BOGUS}).
   * </ul>
   */
  private static String normaliseVerdict(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    RbacDecision only = null;
    boolean multiple = false;
    for (String s : raw) {
      if (s == null) {
        continue;
      }
      String t = s.trim().toUpperCase(Locale.ROOT);
      if (t.isEmpty()) {
        continue;
      }
      RbacDecision parsed;
      try {
        parsed = RbacDecision.valueOf(t);
      } catch (IllegalArgumentException e) {
        throw new ApiBadRequestException(
            "query parameter 'verdict' is not a recognised value: " + s);
      }
      if (only == null) {
        only = parsed;
      } else if (only != parsed) {
        multiple = true;
      }
    }
    if (only == null || multiple) {
      return null;
    }
    return only.name();
  }

  /** Validate {@code scopeKind} against the {@link ScopeKind} enum — unknown kind → HTTP 400. */
  private static String validateScopeKind(String value) {
    if (value == null) {
      return null;
    }
    try {
      return ScopeKind.valueOf(value.toUpperCase(Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new ApiBadRequestException(
          "query parameter 'scopeKind' is not a recognised value: " + value);
    }
  }

  private static Instant parseSince(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new ApiBadRequestException(
          "query parameter 'since' must be an ISO-8601 timestamp (e.g. 2026-05-01T00:00:00Z)");
    }
  }
}
