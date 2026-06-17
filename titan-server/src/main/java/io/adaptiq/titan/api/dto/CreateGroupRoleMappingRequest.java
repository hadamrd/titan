package io.adaptiq.titan.api.dto;

/**
 * Wire format for {@code POST /api/v1/orgs/{orgId}/sso/mappings} and {@code PUT
 * /api/v1/orgs/{orgId}/sso/mappings/{id}} (closes #1136). {@code groupPath} is required on POST and
 * ignored on PUT (the path key drives the row identity; only the role is mutable).
 */
public record CreateGroupRoleMappingRequest(String groupPath, String role) {}
