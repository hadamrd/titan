package io.adaptiq.titan.auth;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.store.GroupRoleMappingDao;
import io.adaptiq.titan.store.rows.GroupRoleMappingRow;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolve the OIDC {@code groups} claim against the persisted {@code group_role_mapping} rows to
 * produce the effective per-org Titan role set for an authenticated subject (closes #1136).
 *
 * <p>Pure logic, no I/O beyond the supplied DAO. Built deliberately so the same call can be made
 * from the OIDC login filter AND from a unit test with an in-memory mapping list.
 *
 * <h2>Adversarial inputs are handled, not crashed on</h2>
 *
 * <ul>
 *   <li>Null / absent claim — empty result; login proceeds with whatever the existing OIDC pipeline
 *       assigned (typically VIEWER).
 *   <li>Claim of the wrong shape (e.g. a single string instead of a list) — best-effort coerce; a
 *       single non-blank string is treated as a one-element list, anything else is dropped with a
 *       {@code WARNING} log. Login MUST NOT 500.
 *   <li>A persisted mapping row whose {@code role} is not in {@link
 *       GroupMappingValidator#SUPPORTED_ROLES} (e.g. a role that has since been deleted from the
 *       enum) — the row is skipped with a {@code WARNING} log.
 *   <li>Multiple mappings on the same org granting different roles — the resolver keeps the union
 *       (a user with ADMIN-by-group-A and DEVELOPER-by-group-B holds both), and exposes a sister
 *       convenience that returns the highest-priority single role per org for callers that want one
 *       label.
 * </ul>
 */
public final class GroupRoleResolver {

  private static final Logger LOGGER = Logger.getLogger(GroupRoleResolver.class.getName());

  /**
   * Priority order, highest → lowest, used by {@link #highestRolePerOrg(Map)}. Matches the
   * intuitive ladder admins use: ADMIN beats MAINTAINER beats DEVELOPER beats VIEWER.
   */
  private static final List<String> ROLE_PRIORITY =
      List.of("ADMIN", "MAINTAINER", "DEVELOPER", "VIEWER");

  private final GroupRoleMappingDao dao;

  public GroupRoleResolver(@NonNull GroupRoleMappingDao dao) {
    this.dao = dao;
  }

  /**
   * Coerce a raw OIDC {@code groups} claim (which the OIDC stack hands us as {@code Object} — could
   * be a list, a single string, null, or something weirder) into a clean list of full-path group
   * strings.
   *
   * <p>Public to make the coercion testable on its own; the resolver calls it internally before any
   * DB read.
   */
  @NonNull
  public static List<String> coerceClaim(@Nullable Object rawClaim) {
    if (rawClaim == null) {
      return List.of();
    }
    if (rawClaim instanceof Collection<?> coll) {
      List<String> out = new ArrayList<>(coll.size());
      for (Object o : coll) {
        if (o instanceof String s && !s.isBlank()) {
          out.add(s.trim());
        }
      }
      return Collections.unmodifiableList(out);
    }
    if (rawClaim instanceof String s) {
      if (s.isBlank()) {
        return List.of();
      }
      // Single-string fallback: some IdPs emit a CSV in one string. Split on comma so the
      // login flow keeps working, but log it so the operator can fix the realm config.
      if (s.contains(",")) {
        LOGGER.log(
            Level.WARNING,
            "[titan] OIDC groups claim is a CSV string, not a list — coercing to list. "
                + "Fix the realm protocol-mapper to multivalued.");
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
          String t = part.trim();
          if (!t.isEmpty()) {
            out.add(t);
          }
        }
        return Collections.unmodifiableList(out);
      }
      return List.of(s.trim());
    }
    LOGGER.log(
        Level.WARNING,
        "[titan] OIDC groups claim has unexpected type {0}; dropping.",
        rawClaim.getClass().getName());
    return List.of();
  }

  /**
   * Compute the effective {@code Map<orgId, Set<role>>} given a raw {@code groups} claim. Reads the
   * mapping rows for the supplied group paths in a single round trip; empty / unknown groups
   * contribute nothing.
   */
  @NonNull
  public Map<String, Set<String>> resolve(@Nullable Object rawClaim) {
    List<String> groups = coerceClaim(rawClaim);
    if (groups.isEmpty()) {
      return Map.of();
    }
    List<GroupRoleMappingRow> rows = dao.listByGroupPaths(groups);
    return foldRows(rows);
  }

  /**
   * Same as {@link #resolve(Object)} but takes the already-coerced list of mapping rows directly —
   * exposed for unit tests so they can exercise the role-aggregation logic without standing up a
   * DAO.
   */
  @NonNull
  public static Map<String, Set<String>> foldRows(@NonNull List<GroupRoleMappingRow> rows) {
    Map<String, Set<String>> byOrg = new HashMap<>();
    for (GroupRoleMappingRow row : rows) {
      if (row == null || row.orgId == null || row.role == null) {
        continue;
      }
      String role = row.role.trim().toUpperCase(Locale.ROOT);
      if (!GroupMappingValidator.SUPPORTED_ROLES.contains(role)) {
        // A role that's been deleted from the enum, or a corrupt row. Skip — never 500.
        LOGGER.log(
            Level.WARNING,
            "[titan] group_role_mapping row id={0} carries unknown role {1}; skipping",
            new Object[] {row.id, row.role});
        continue;
      }
      byOrg.computeIfAbsent(row.orgId, k -> new LinkedHashSet<>()).add(role);
    }
    // Make the inner sets immutable to deny accidental mutation by callers.
    Map<String, Set<String>> out = new HashMap<>(byOrg.size());
    for (Map.Entry<String, Set<String>> e : byOrg.entrySet()) {
      out.put(e.getKey(), Set.copyOf(e.getValue()));
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * Project a multi-role-per-org result down to a single role per org by priority. Highest first
   * (ADMIN beats MAINTAINER beats …). Returns an empty map if the input is empty. Used by call
   * sites that want one label per org (e.g. UI badge in the user dropdown).
   */
  @NonNull
  public static Map<String, String> highestRolePerOrg(@NonNull Map<String, Set<String>> in) {
    Map<String, String> out = new HashMap<>(in.size());
    for (Map.Entry<String, Set<String>> e : in.entrySet()) {
      String best = null;
      for (String r : ROLE_PRIORITY) {
        if (e.getValue().contains(r)) {
          best = r;
          break;
        }
      }
      if (best != null) {
        out.put(e.getKey(), best);
      }
    }
    return Collections.unmodifiableMap(out);
  }
}
