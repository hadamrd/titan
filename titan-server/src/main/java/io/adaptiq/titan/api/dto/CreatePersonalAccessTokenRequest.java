package io.adaptiq.titan.api.dto;

import java.util.List;

/**
 * Wire format for {@code POST /api/v1/me/tokens} (closes #434).
 *
 * <p>{@code name}: human-readable label (required, &le; 100 chars).
 *
 * <p>{@code scopes} (added by #500): optional list of role strings that narrow the resulting PAT
 * identity to (creator's roles) ∩ (scopes). {@code null} or empty list = legacy behaviour, the PAT
 * inherits the creator's full non-admin role set. The server validates each entry against {@link
 * io.adaptiq.titan.auth.Roles} and returns 400 problem+json on any unknown scope.
 *
 * <p>{@code jobPattern} (added by #1082): optional glob over {@code job.full_name} (e.g. {@code
 * "acme/web-*"}, {@code "org/my-app/**"}). When set, every per-job / per-build request
 * authenticated with this PAT MUST resolve to a job whose {@code full_name} matches the glob, or
 * the server returns 403 problem+json and writes a {@code PAT_SCOPE_DENIED} audit row. {@code null}
 * or blank = no path restriction (back-compat with #500). Server-side validation (see {@link
 * io.adaptiq.titan.auth.PatJobPattern}) rejects path-traversal segments, percent-encoded
 * characters, and any glob outside the {@code [A-Za-z0-9._/*?-]} character class.
 */
public record CreatePersonalAccessTokenRequest(
    String name, List<String> scopes, String jobPattern) {}
