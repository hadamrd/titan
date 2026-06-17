package io.adaptiq.titan.auth;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-function validators for SSO group → Titan role mapping inputs (closes #1136).
 *
 * <p>Lives in {@code titan-server} alongside {@link GroupRoleResolver} on purpose: the same
 * vocabulary (legal role enum, legal group-path shape) is enforced at three call sites — the REST
 * input validator on {@code POST /api/v1/orgs/{id}/sso/mappings}, the resolver when reading
 * persisted rows back out (so a bad row can never crash login), and unit tests asserting both.
 *
 * <p>String-literal role comparisons are FORBIDDEN at the call site (manifesto: no stringly-typed
 * cross-module discriminators); callers go through {@link #SUPPORTED_ROLES} or the {@code Roles.*}
 * constants. The validator itself is the single point where the wire shape becomes a known enum.
 */
public final class GroupMappingValidator {

  /**
   * The closed enum of Titan roles that may appear on the wire in an SSO mapping row. Pulled from
   * the spec on #1136: {@code admin | maintainer | developer | viewer}. Case is normalised to
   * upper-case before comparison so YAML-style {@code admin} and Java-style {@code ADMIN} are
   * equivalent inputs.
   */
  public static final Set<String> SUPPORTED_ROLES =
      Set.of("ADMIN", "MAINTAINER", "DEVELOPER", "VIEWER");

  /**
   * Wire-format pattern for a Keycloak full-path group: leading slash, then one or more segments of
   * letters / digits / underscore / hyphen separated by slashes. No trailing slash, no double
   * slash, no whitespace.
   */
  private static final Pattern GROUP_PATH_PATTERN = Pattern.compile("^/[A-Za-z0-9_\\-/]+$");

  private static final int MAX_GROUP_PATH_LEN = 512;

  private GroupMappingValidator() {}

  /**
   * Thrown by the validators on bad input. The REST layer maps this to a 400 problem+json; tests
   * assert on the typed exception, not on the message.
   */
  public static class InvalidGroupMappingException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidGroupMappingException(String message) {
      super(message);
    }
  }

  /**
   * Normalise and validate a group path. Returns the canonical form (no surrounding whitespace,
   * preserved case). Throws {@link InvalidGroupMappingException} on any deviation from the {@code
   * ^/[A-Za-z0-9_\-/]+$} shape — including empty, null, double slashes, trailing slash, embedded
   * whitespace, or over-length input.
   */
  @NonNull
  public static String validateGroupPath(String raw) {
    if (raw == null) {
      throw new InvalidGroupMappingException("group_path is required");
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      throw new InvalidGroupMappingException("group_path is required");
    }
    if (trimmed.length() > MAX_GROUP_PATH_LEN) {
      throw new InvalidGroupMappingException(
          "group_path must be <= " + MAX_GROUP_PATH_LEN + " characters");
    }
    if (trimmed.endsWith("/")) {
      throw new InvalidGroupMappingException("group_path must not end with '/'");
    }
    if (trimmed.contains("//")) {
      throw new InvalidGroupMappingException("group_path must not contain '//'");
    }
    if (!GROUP_PATH_PATTERN.matcher(trimmed).matches()) {
      throw new InvalidGroupMappingException(
          "group_path must match " + GROUP_PATH_PATTERN.pattern());
    }
    return trimmed;
  }

  /**
   * Normalise (upper-case + trim) and validate a role. Returns the canonical {@code Roles.*} form
   * (one of {@link #SUPPORTED_ROLES}). Throws {@link InvalidGroupMappingException} on null, blank,
   * or any value outside the enum.
   */
  @NonNull
  public static String validateRole(String raw) {
    if (raw == null) {
      throw new InvalidGroupMappingException("role is required");
    }
    String norm = raw.trim().toUpperCase(java.util.Locale.ROOT);
    if (norm.isEmpty()) {
      throw new InvalidGroupMappingException("role is required");
    }
    if (!SUPPORTED_ROLES.contains(norm)) {
      throw new InvalidGroupMappingException(
          "role must be one of " + sorted(SUPPORTED_ROLES) + " (got " + raw + ")");
    }
    return norm;
  }

  /**
   * Validate an org id. Same shape as a group path's first segment — letters / digits / underscore
   * / hyphen — but without a leading slash and capped tighter. We don't yet share an OrgId type
   * with the rest of the codebase; this is the local validator.
   */
  @NonNull
  public static String validateOrgId(String raw) {
    if (raw == null) {
      throw new InvalidGroupMappingException("org_id is required");
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      throw new InvalidGroupMappingException("org_id is required");
    }
    if (trimmed.length() > 128) {
      throw new InvalidGroupMappingException("org_id must be <= 128 characters");
    }
    if (!trimmed.matches("^[A-Za-z0-9_\\-]+$")) {
      throw new InvalidGroupMappingException("org_id must match ^[A-Za-z0-9_\\-]+$");
    }
    return trimmed;
  }

  private static String sorted(Set<String> in) {
    Set<String> out = new LinkedHashSet<>();
    in.stream().sorted().forEach(out::add);
    return out.toString();
  }
}
