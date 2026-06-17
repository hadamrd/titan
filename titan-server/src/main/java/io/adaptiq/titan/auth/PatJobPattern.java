package io.adaptiq.titan.auth;

import java.util.regex.Pattern;

/**
 * Glob matcher and validator for the per-PAT {@code job_pattern} restriction (closes #1082).
 *
 * <p>Job patterns are case-sensitive, slash-separated globs over a job's {@code full_name} (e.g.
 * {@code "acme/web"}, {@code "org/my-app/release/v2"}). The supported wildcards mirror the subset
 * GitHub Actions and Buildkite expose — enough to express the common "one app", "one org", "all
 * release-X branches" cases without inheriting the full POSIX-glob attack surface.
 *
 * <h2>Grammar</h2>
 *
 * <ul>
 *   <li>{@code *} — matches zero or more characters EXCEPT {@code /}. Scoped to a single path
 *       segment.
 *   <li>{@code **} — matches zero or more characters INCLUDING {@code /}. Used for "everything
 *       under this prefix".
 *   <li>{@code ?} — matches exactly one character (not {@code /}).
 *   <li>Every other character matches itself literally.
 * </ul>
 *
 * <h2>Security guards</h2>
 *
 * <ul>
 *   <li><strong>Pattern shape (mint-time):</strong> {@link #validate(String)} rejects empty, blank,
 *       over-long ({@code > MAX_PATTERN_LEN}), or character-set-illegal patterns. Only {@code
 *       a-zA-Z0-9._/-} plus the three glob meta-characters {@code * ? \} are accepted — this is
 *       what GitHub allows on a repo name, modulo case. Anything outside that set (including
 *       percent-encoded escape sequences and path-traversal sequences like {@code ../}) is
 *       rejected. A pattern that contains a literal {@code %} is also rejected so an operator
 *       cannot accidentally try to write a percent-encoded glob.
 *   <li><strong>Candidate shape (match-time):</strong> {@link #matches(String, String)} rejects any
 *       candidate that contains a {@code %} character outright. JAX-RS path-parameter extraction
 *       has ALREADY URL-decoded the path-parameter value by the time it lands in our
 *       {@code @PathParam}, so a request like {@code /api/v1/jobs/acme%2Fweb/builds} surfaces as
 *       the resolved job's actual {@code full_name} — but defence-in-depth says we still hard-bail
 *       if we ever see a {@code %} sneak through (e.g. a future controller that bypasses JAX-RS),
 *       preventing a {@code "acme%2f**"}-style pattern from matching a {@code "acme/**"} request
 *       and vice-versa. {@code ..} segments are also rejected to lock down any future
 *       path-traversal vector.
 *   <li><strong>No regex injection:</strong> we compile the glob to a regex internally, but every
 *       literal character is run through {@link Pattern#quote(String)} so meta-characters from a
 *       hostile pattern (impossible given the validator, but belt-and-braces) cannot escape into
 *       the regex.
 * </ul>
 *
 * <h2>Empty / null semantics</h2>
 *
 * <ul>
 *   <li>{@link #validate(String)}: {@code null} or blank → {@code null} (caller treats as "no
 *       restriction"). Otherwise returns the trimmed pattern or throws.
 *   <li>{@link #matches(String, String)}: a {@code null} pattern is "match everything" (no
 *       restriction); a {@code null} candidate is treated as "match nothing" — a candidate that we
 *       could not resolve must not silently bypass a restriction.
 * </ul>
 *
 * <p>This class is final + utility (private ctor). No CDI bean — it has no state.
 */
public final class PatJobPattern {

  /** Hard cap on the stored pattern length. Matches the V34 column width. */
  public static final int MAX_PATTERN_LEN = 200;

  /**
   * Allowed characters in a job pattern: GitHub-repo-name safe set ({@code a-zA-Z0-9._-}), the path
   * separator {@code /}, and the three glob meta-characters {@code *}, {@code ?}.
   *
   * <p>NOT in the set: {@code %} (URL-encode), {@code \\} (Windows / escape), whitespace, control
   * characters, brackets, braces, colons, semicolons, ampersands, quotes — anything that could
   * round-trip through a URL parser or shell with surprising semantics.
   */
  private static final Pattern ALLOWED_CHARS = Pattern.compile("^[A-Za-z0-9._/*?-]+$");

  private PatJobPattern() {}

  /**
   * Validate a caller-supplied pattern. {@code null} or blank input → {@code null}-out (legacy "no
   * restriction"). Otherwise trims and returns the canonical form, or throws {@link
   * InvalidPatJobPatternException}.
   */
  public static String validate(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    if (trimmed.length() > MAX_PATTERN_LEN) {
      throw new InvalidPatJobPatternException(
          "jobPattern must be <= " + MAX_PATTERN_LEN + " characters");
    }
    if (!ALLOWED_CHARS.matcher(trimmed).matches()) {
      // Hits the percent-encoding bypass attempt, embedded whitespace, control chars, etc.
      throw new InvalidPatJobPatternException(
          "jobPattern contains disallowed characters; allowed: a-z A-Z 0-9 . _ - / * ?");
    }
    // Reject path-traversal sequences outright — Titan job full_names never contain `..` and a
    // glob with `..` is almost always an attempted bypass.
    if (trimmed.contains("..")) {
      throw new InvalidPatJobPatternException("jobPattern must not contain '..'");
    }
    // Reject `***` (and longer runs) — the grammar is `*` and `**`. Three+ stars is almost always
    // a typo or an attempt to abuse our regex compile path; we cap it so the user gets a clean
    // 400 instead of a surprising match.
    if (trimmed.contains("***")) {
      throw new InvalidPatJobPatternException("jobPattern must not contain '***'");
    }
    return trimmed;
  }

  /**
   * Match a resolved job {@code full_name} against the validated {@code pattern}.
   *
   * <p>{@code pattern == null} → always {@code true} (no restriction). {@code candidate == null} or
   * empty → {@code false}: we will NEVER grant access to a request we could not pin to a job name.
   * {@code candidate} containing a {@code %} or {@code ..} segment → {@code false}: a suspicious
   * candidate fails closed.
   */
  public static boolean matches(String pattern, String candidate) {
    if (pattern == null) {
      return true;
    }
    if (candidate == null || candidate.isEmpty()) {
      return false;
    }
    if (candidate.indexOf('%') >= 0) {
      // The candidate should have been URL-decoded by the JAX-RS path-param binder before we see
      // it. A surviving `%` means either a malformed full_name (DB row hand-edited) or a future
      // bypass attempt — fail closed.
      return false;
    }
    if (containsTraversalSegment(candidate)) {
      return false;
    }
    return compile(pattern).matcher(candidate).matches();
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  /** Compile the validated glob to a regex. Validation guarantees the input is well-formed. */
  private static Pattern compile(String glob) {
    StringBuilder sb = new StringBuilder(glob.length() * 2 + 4);
    sb.append("\\A"); // anchor start
    int i = 0;
    while (i < glob.length()) {
      char c = glob.charAt(i);
      if (c == '*') {
        // `**` consumes /, `*` does not.
        if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
          sb.append(".*");
          i += 2;
        } else {
          sb.append("[^/]*");
          i++;
        }
      } else if (c == '?') {
        sb.append("[^/]");
        i++;
      } else {
        // Literal char — quote so regex meta-characters CANNOT leak through. The validator
        // already restricts to a safe character set, but the quote is cheap defence-in-depth.
        sb.append(Pattern.quote(String.valueOf(c)));
        i++;
      }
    }
    sb.append("\\z"); // anchor end
    return Pattern.compile(sb.toString());
  }

  /** Does the candidate path contain a literal {@code ..} segment? */
  private static boolean containsTraversalSegment(String candidate) {
    if (candidate.equals("..")) {
      return true;
    }
    if (candidate.startsWith("../")) {
      return true;
    }
    if (candidate.endsWith("/..")) {
      return true;
    }
    return candidate.contains("/../");
  }

  /** Thrown by {@link #validate} on any malformed pattern; mapped to 400 by the API. */
  public static final class InvalidPatJobPatternException extends RuntimeException {
    public InvalidPatJobPatternException(String message) {
      super(message);
    }
  }
}
