package io.adaptiq.titan.auth;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.api.PersonalAccessTokenApi;
import io.adaptiq.titan.store.PersonalAccessTokenDao;
import io.adaptiq.titan.store.TitanStores;
import io.adaptiq.titan.store.rows.PersonalAccessTokenRow;
import io.quarkus.elytron.security.common.BcryptUtil;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;

// PatScopes is in the same package — no import.

/**
 * Stateless verifier for personal-access-token bearer strings — extracted from {@link
 * PatAuthenticationMechanism} so the verification path can be unit-tested without standing up the
 * full Quarkus HTTP stack.
 *
 * <p>Closes #477. Companion to {@link PersonalAccessTokenApi} (CRUD) — this class is the read /
 * verify half of the same surface.
 *
 * <h2>Security invariants</h2>
 *
 * <ul>
 *   <li>The plaintext token is NEVER logged. Log lines reference at most the public {@code prefix}
 *       (which is also surfaced in the UI / list response) and the row id.
 *   <li>Hash compare goes through {@link BcryptUtil#matches} which delegates to {@code
 *       BCrypt.checkpw} — constant-time within the BCrypt impl. No {@code String.equals} fallback.
 *   <li>Revoked tokens are filtered at the DAO level ({@code revoked_at IS NULL}) — a revoked PAT
 *       never reaches the {@code checkpw} step.
 *   <li>Wrong-prefix bearers ({@code titanpat_} not present, or shape mismatch) short-circuit
 *       without a DB hit — see {@link #looksLikePat}.
 * </ul>
 */
@ApplicationScoped
public class PatTokenVerifier {

  private static final Logger LOG = Logger.getLogger(PatTokenVerifier.class);

  /** Mirrors {@link PersonalAccessTokenApi#TOKEN_PREFIX}. */
  public static final String TOKEN_PREFIX = PersonalAccessTokenApi.TOKEN_PREFIX;

  /** Indexed prefix length stored on the row. Mirrors {@code PersonalAccessTokenApi}. */
  static final int STORED_PREFIX_LEN = TOKEN_PREFIX.length() + 4;

  /**
   * Minimum plausible total length: {@code "titanpat_"} (9) + 4 chars of indexed prefix + at least
   * a few more random chars. We accept anything that is at least {@code STORED_PREFIX_LEN + 1}
   * long; the BCrypt compare is the source of truth.
   */
  static final int MIN_TOKEN_LEN = STORED_PREFIX_LEN + 1;

  private final TitanStores stores;

  PatTokenVerifier(TitanStores stores) {
    this.stores = stores;
  }

  /**
   * Does {@code bearer} look like a PAT? Used as a fast discriminator so {@link
   * PatAuthenticationMechanism} can decide whether to claim the request or punt to the OIDC
   * mechanism. Returns {@code false} for {@code null}, the empty string, OIDC JWTs, etc.
   *
   * <p>This is a SHAPE check, not a validity check — a malformed PAT still returns {@code true}
   * here and is rejected later by {@link #verify}.
   */
  public static boolean looksLikePat(String bearer) {
    return bearer != null && bearer.startsWith(TOKEN_PREFIX);
  }

  /**
   * Verify a bearer token and return the owning user's subject on success.
   *
   * <p>Returns {@link Optional#empty()} for ANY failure — bad shape, no DB match, hash mismatch,
   * revoked, or an unexpected exception. The mechanism layer turns empty into HTTP 401.
   *
   * <p>On success, {@link PersonalAccessTokenDao#touchLastUsed(long)} is invoked best-effort; a
   * failure to stamp {@code last_used_at} does NOT fail authentication (auth is the source of
   * truth, telemetry is secondary).
   */
  public Optional<VerifiedPat> verify(@NonNull String bearer) {
    if (!looksLikePat(bearer) || bearer.length() < MIN_TOKEN_LEN) {
      return Optional.empty();
    }
    final String prefix = bearer.substring(0, STORED_PREFIX_LEN);
    final PersonalAccessTokenDao dao = stores.personalAccessTokens();

    List<PersonalAccessTokenRow> candidates;
    try {
      candidates = dao.findActiveByPrefix(prefix);
    } catch (RuntimeException e) {
      // Treat a store outage as auth failure rather than 500 — surfaces as 401 and is observable
      // via server logs. Logging the prefix only (never the plaintext).
      LOG.warnf(e, "pat-auth: dao lookup failed for prefix=%s", prefix);
      return Optional.empty();
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }

    for (PersonalAccessTokenRow row : candidates) {
      // Belt-and-braces — DAO already filters on revoked_at IS NULL, but verify again so a future
      // DAO refactor cannot silently downgrade this invariant.
      if (row.revokedAt != null) {
        continue;
      }
      if (row.tokenHash == null || row.tokenHash.isEmpty()) {
        continue;
      }
      // BcryptUtil.matches → BCrypt.checkpw — constant-time within BCrypt's impl.
      if (BcryptUtil.matches(bearer, row.tokenHash)) {
        try {
          dao.touchLastUsed(row.id);
        } catch (RuntimeException e) {
          // last_used_at is telemetry — do not fail auth on a stamp failure.
          LOG.debugf(e, "pat-auth: touchLastUsed failed for id=%d (ignored)", row.id);
        }
        // #500: parse the per-token scope list (null = legacy "inherit all").
        List<String> scopes = PatScopes.parseOrNull(row.scopesJson);
        // #1082: carry the optional job-name glob through to the request filter that enforces it.
        return Optional.of(
            new VerifiedPat(row.id, row.userSubject, prefix, scopes, row.jobPattern));
      }
    }
    return Optional.empty();
  }

  /**
   * Result of a successful PAT verification — never carries the plaintext or the hash.
   *
   * <p>{@code scopes} (closes #500): the per-token scope list, or {@code null} for legacy tokens
   * that should inherit the creator's full role set. The mechanism layer intersects with the
   * granted role set via {@link PatScopes#intersect(java.util.Set, List)}.
   *
   * <p>{@code jobPattern} (closes #1082): the per-token job-name glob, or {@code null} for tokens
   * with no path restriction. The {@code PatJobScopeFilter} enforces it on the request path; this
   * verifier merely surfaces the value.
   */
  public record VerifiedPat(
      long tokenId, String userSubject, String prefix, List<String> scopes, String jobPattern) {}
}
