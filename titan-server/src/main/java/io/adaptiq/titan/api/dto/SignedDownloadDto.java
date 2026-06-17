package io.adaptiq.titan.api.dto;

import static com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Response shape for {@code POST /api/v1/artifacts/{id}/sign-download} (closes #849).
 *
 * <p>The browser-side UI POSTs to /sign-download (carrying its bearer header), gets back this
 * envelope, and uses {@link #url} as the {@code <iframe src=>} / {@code <a href=>} target — a
 * navigation that no longer needs the bearer because the URL itself carries an HMAC-signed token.
 *
 * <p>{@link #expiresAt} is the absolute wall-clock instant of the token expiry — the UI uses it to
 * compute a "refetch when within N seconds of expiry" cache policy without ever parsing the token
 * (the token's internal shape is a server-only contract).
 *
 * <p>The HMAC secret is NEVER projected here, only the signed URL.
 */
@JsonInclude(NON_NULL)
public record SignedDownloadDto(String url, Instant expiresAt) {}
