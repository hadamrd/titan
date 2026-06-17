package io.adaptiq.titan.api.dto;

import io.adaptiq.titan.store.rows.GroupRoleMappingRow;
import java.time.Instant;

/**
 * Wire format for a single SSO group → Titan role mapping row (closes #1136). Returned by {@code
 * GET /api/v1/orgs/{orgId}/sso/mappings} and the create/update endpoints.
 *
 * <p>{@code id}: stable row id; the path key on PUT / DELETE.
 *
 * <p>{@code orgId}: the org the mapping is scoped to. Echoes the path param.
 *
 * <p>{@code groupPath}: full-path Keycloak group (e.g. {@code /acme/platform-admins}).
 *
 * <p>{@code role}: one of {@code ADMIN | MAINTAINER | DEVELOPER | VIEWER}; the validator enforces.
 */
public record GroupRoleMappingDto(
    long id, String orgId, String groupPath, String role, Instant createdAt, Instant updatedAt) {

  public static GroupRoleMappingDto from(GroupRoleMappingRow row) {
    return new GroupRoleMappingDto(
        row.id, row.orgId, row.groupPath, row.role, row.createdAt, row.updatedAt);
  }
}
