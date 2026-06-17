package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * API response DTO for a Keycloak realm user, used by {@code GET /api/v1/admin/users} (backend half
 * of #603).
 *
 * <p>Wire format is deliberately a narrow projection of the Keycloak admin REST {@code
 * UserRepresentation} — only fields the admin UI needs are exposed, so we can swap the IdP later
 * without breaking the client. {@code displayName} prefers {@code firstName + lastName}, falling
 * back to {@code username}. {@code realmRoles} is the user's effective realm-role set (filtered to
 * titan roles by the caller if desired).
 *
 * <p>{@code titanRoles} carries the user's scoped Titan role assignments from {@code
 * titan.rbac_user_role} (epic #1114 item 6, closes #1236), enriched by {@code AdminUsersApi#list}.
 * It is distinct from {@code realmRoles} (the coarse Keycloak realm roles): Titan roles are the
 * per-scope RBAC grants the {@code /users} UI lets an admin manage. Defaults to an empty list so a
 * user with no scoped grants serialises {@code "titanRoles": []} (not null).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserDto(
    String username,
    String email,
    String displayName,
    List<String> realmRoles,
    List<RoleAssignmentDto> titanRoles) {

  /**
   * Compatibility constructor for callers (e.g. {@code KeycloakAdminClient}) that build a user from
   * the IdP projection alone, before scoped Titan-role enrichment runs. Defaults {@code titanRoles}
   * to an empty list; {@code AdminUsersApi#list} rebuilds the record with the assignments.
   */
  public UserDto(String username, String email, String displayName, List<String> realmRoles) {
    this(username, email, displayName, realmRoles, List.of());
  }
}
