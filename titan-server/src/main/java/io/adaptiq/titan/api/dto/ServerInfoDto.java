package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code GET /api/v1/info} — public server-identity tile (closes Forge-loop tick
 * #48). All four fields are always present; missing build-time data degrades to the documented
 * fallback rather than to {@code null} so the UI never has to defensively type-narrow.
 *
 * <ul>
 *   <li>{@code version} — the Gradle project version (e.g. {@code "0.1.0"}); falls back to {@code
 *       "unknown"} if {@code META-INF/titan-build-info.properties} is absent from the classpath.
 *   <li>{@code commit} — short git SHA (7 chars) of the build; {@code "unknown"} when the build
 *       didn't write a properties file (IDE classpath, follow-up wiring pending).
 *   <li>{@code builtAt} — ISO-8601 UTC timestamp written at compile time; {@code "unknown"} as
 *       above.
 *   <li>{@code uptimeSeconds} — seconds since the JVM started, computed on every request.
 * </ul>
 *
 * <p>The endpoint is {@code @PermitAll} — version + commit + uptime are not secrets.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ServerInfoDto(String version, String commit, String builtAt, long uptimeSeconds) {}
