package io.adaptiq.titan.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Single source of truth for PAT scope parsing, validation, and intersection (closes #500).
 *
 * <h2>Wire vs. server</h2>
 *
 * On the wire a scope is a string (the role name); server-side that string is validated against
 * {@link #ALLOWED}. {@link Roles#ADMIN} is NOT a grantable PAT scope — admin power is reserved for
 * OIDC sessions, mirroring the cap in {@link PatAuthenticationMechanism}.
 *
 * <h2>Storage</h2>
 *
 * Scopes are serialised as a JSON array string and stored in {@code
 * titan.personal_access_tokens.scopes_json}. {@code null} (or whitespace-only) on the DB column
 * means "legacy / inherit all roles" and {@link #parseOrNull} returns {@code null} for that case.
 */
public final class PatScopes {

  private static final Logger LOG = Logger.getLogger(PatScopes.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {};

  /**
   * Grantable scopes for a PAT. Mirrors the non-admin role set the bearer-auth mechanism is willing
   * to project (see {@link PatAuthenticationMechanism}). Kept in sync by construction: the verifier
   * intersects against this same set.
   */
  public static final Set<String> ALLOWED =
      Set.of(
          Roles.READ_JOB,
          Roles.TRIGGER_BUILD,
          Roles.EDIT_PIPELINE,
          Roles.APPROVE_GATE,
          Roles.READ_AUDIT,
          Roles.ABORT_BUILD,
          Roles.OPERATE_WORKER,
          Roles.REPLAY_BUILD,
          Roles.MANAGE_CREDENTIALS);

  private PatScopes() {}

  /**
   * Validate a caller-supplied scope list against {@link #ALLOWED}.
   *
   * @return canonical (deduplicated, sorted-by-insertion) list; {@code null} if {@code raw} is null
   *     OR empty → meaning legacy "inherit all".
   * @throws InvalidPatScopeException if any entry is null/blank/unknown.
   */
  public static List<String> validate(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    LinkedHashSet<String> normalised = new LinkedHashSet<>();
    for (String scope : raw) {
      if (scope == null || scope.isBlank()) {
        throw new InvalidPatScopeException("scope entry is null or blank");
      }
      String trimmed = scope.trim();
      if (!ALLOWED.contains(trimmed)) {
        throw new InvalidPatScopeException("unknown scope: " + trimmed);
      }
      normalised.add(trimmed);
    }
    return List.copyOf(normalised);
  }

  /**
   * Serialise a validated scope list to JSON for persistence. {@code null}-in → {@code null}-out.
   */
  public static String toJson(List<String> scopes) {
    if (scopes == null) {
      return null;
    }
    try {
      return MAPPER.writeValueAsString(scopes);
    } catch (Exception e) {
      // Should be impossible — List<String> always serialises. Treat as a programming error.
      throw new IllegalStateException("PatScopes: cannot serialise scope list", e);
    }
  }

  /**
   * Parse the {@code scopes_json} blob from a row. Returns {@code null} for null/blank input
   * (legacy "inherit all" semantics). Malformed JSON is logged at WARN and treated as null — the
   * auth path will then fall back to "no scope restriction", which is the safe, backward-compatible
   * default. (Alternative: fail closed; we choose fail-open because PAT scopes are an *additional*
   * restriction layer above the per-route {@code @RolesAllowed}, and a stuck PAT cannot escalate
   * past the role cap baked into {@link PatAuthenticationMechanism}.)
   */
  public static List<String> parseOrNull(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      List<String> out = MAPPER.readValue(json, LIST_OF_STRING);
      if (out == null || out.isEmpty()) {
        return null;
      }
      // Defensive: strip any disallowed/empty entries silently — DB content is trusted because
      // only validate() writes to it, but a downgrade or hand-edited row should not crash auth.
      List<String> clean = new ArrayList<>(out.size());
      for (String s : out) {
        if (s != null && !s.isBlank() && ALLOWED.contains(s.trim())) {
          clean.add(s.trim());
        }
      }
      return clean.isEmpty() ? null : Collections.unmodifiableList(clean);
    } catch (Exception e) {
      LOG.warnf(e, "pat-scopes: malformed scopes_json (treating as unrestricted): %s", json);
      return null;
    }
  }

  /**
   * Compute the effective role set for a PAT-authenticated request.
   *
   * <p>{@code patScopes == null} → return {@code grantedRoles} unchanged (legacy behaviour).
   * Otherwise return the intersection — never the union. This is the core security invariant of
   * #500: a PAT can ONLY ever shrink its creator's effective permissions, never widen them.
   */
  public static Set<String> intersect(Set<String> grantedRoles, List<String> patScopes) {
    if (patScopes == null) {
      return grantedRoles;
    }
    Set<String> scopeSet = Set.copyOf(patScopes);
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String role : grantedRoles) {
      if (scopeSet.contains(role)) {
        out.add(role);
      }
    }
    return Set.copyOf(out);
  }

  /** Thrown by {@link #validate} on any unknown / blank scope entry; mapped to 400 by the API. */
  public static final class InvalidPatScopeException extends RuntimeException {
    public InvalidPatScopeException(String message) {
      super(message);
    }
  }
}
