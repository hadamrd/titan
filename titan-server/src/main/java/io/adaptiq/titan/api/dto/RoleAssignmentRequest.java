package io.adaptiq.titan.api.dto;

/**
 * Request body for {@code PUT /api/v1/admin/users/{userId}/roles} — grant a Titan role to a user on
 * a scope (epic #1114 item 6, closes #1236).
 *
 * <p>All three fields are required. {@code role} must parse to an {@link
 * io.adaptiq.titan.auth.Authz.TitanRole} constant and {@code scopeKind} to a {@link
 * io.adaptiq.titan.auth.ScopeKind} constant; an unknown value is a 400 problem+json at the handler
 * (never silently coerced — manifesto "no stringly-typed cross-module discriminators"). {@code
 * scopeId} is the org slug (for {@code ORG}, e.g. {@code "global"}) or {@code "<org>/<repo>"} (for
 * {@code REPO}).
 */
public record RoleAssignmentRequest(String role, String scopeKind, String scopeId) {}
