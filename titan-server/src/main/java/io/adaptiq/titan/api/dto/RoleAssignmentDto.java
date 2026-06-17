package io.adaptiq.titan.api.dto;

/**
 * API response DTO for one scoped Titan role assignment — a row in {@code titan.rbac_user_role}
 * (epic #1114 item 6, closes #1236).
 *
 * <p>Returned by {@code PUT /api/v1/admin/users/{userId}/roles} (the resulting role set) and
 * embedded in {@link UserDto#titanRoles()} on {@code GET /api/v1/admin/users} so the {@code /users}
 * admin UI can render which Titan roles each user holds without a second round-trip.
 *
 * <p>{@code scopeKind} is the {@link io.adaptiq.titan.auth.ScopeKind} name ({@code ORG} / {@code
 * REPO}); {@code role} is the {@link io.adaptiq.titan.auth.Authz.TitanRole} name. Both are emitted
 * as the enum constant name (already validated at the write boundary), never a free string.
 */
public record RoleAssignmentDto(String role, String scopeKind, String scopeId) {}
