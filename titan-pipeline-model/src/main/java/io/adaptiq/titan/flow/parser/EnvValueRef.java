package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The {@code env:} value syntax — a literal string OR a {@code secret:<id>} reference (#1094).
 *
 * <p>A pipeline author writes:
 *
 * <pre>{@code
 * env:
 *   LOG_LEVEL: info                    # literal — passed straight through
 *   GH_TOKEN:  "secret:gh-token"       # resolved at dispatch via CredentialsService
 *   AWS_KEY:   "secret:aws/dev-key"    # scope/key — same convention as CredentialResolver
 * }</pre>
 *
 * <p>This class is the single source of truth for the {@code secret:} prefix shape. It is shared
 * between:
 *
 * <ul>
 *   <li>{@link EnvScope} — rejects malformed references at parse time, so a typo never reaches the
 *       orchestrator.
 *   <li>The server-side {@code EnvResolver} — splits a merged env into plain + secret-resolved, and
 *       calls {@code CredentialsService.resolvePlaintext} on each ref.
 *   <li>{@link io.adaptiq.titan.flow.parser.grammar.TitanGrammar#envMapSchema()} — the schema
 *       pattern that codifies the same rule for any consumer that validates by JSON Schema.
 * </ul>
 *
 * <p><strong>Shape:</strong> {@code secret:<id>} where {@code <id>} is one of:
 *
 * <ul>
 *   <li>a bare key ({@code "gh-token"}) — resolved under the {@code default} scope
 *   <li>a {@code scope/key} tuple ({@code "aws/dev-key"}) — same form as {@code
 *       CredentialBinding.id}.
 * </ul>
 *
 * <p>Anything else (extra colons, empty body, whitespace) is a located parse error — see {@link
 * #validate}. Adversarial inputs covered: {@code secret:} (empty body), {@code secret:nope:extra}
 * (multi-colon), {@code "secret: "} (whitespace-only body).
 *
 * <p>This is a pure value object — no I/O, no state, safe to call from any thread.
 */
public final class EnvValueRef {

  /** The single prefix that turns an env value into a secret reference. */
  public static final String SECRET_PREFIX = "secret:";

  private EnvValueRef() {}

  /**
   * Is the given env value a {@code secret:<id>} reference? Cheap prefix check — does NOT validate
   * the body. Call {@link #validate} for the full check.
   */
  public static boolean isSecretRef(@NonNull String value) {
    return value.startsWith(SECRET_PREFIX);
  }

  /**
   * Extract the credential id from a {@code secret:<id>} reference. Caller is responsible for
   * having validated the value via {@link #validate} first.
   *
   * @throws IllegalArgumentException if the value is not a {@code secret:} reference at all
   */
  @NonNull
  public static String secretId(@NonNull String value) {
    if (!isSecretRef(value)) {
      throw new IllegalArgumentException("not a secret reference: " + value);
    }
    return value.substring(SECRET_PREFIX.length());
  }

  /**
   * Validate a single {@code env:} value at parse time. Literal values are accepted unchanged
   * (Titan does not restrict what characters an environment value may contain). Values that begin
   * with the {@link #SECRET_PREFIX} are required to name exactly one credential id — no extra
   * colons, no empty / blank body. A bad reference throws {@link PipelineParseException} mentioning
   * the offending env-var name so the operator can fix it in the YAML.
   *
   * @param envKey the env var name (e.g. {@code GH_TOKEN}); used in the error message
   * @param value the YAML value as a string
   * @param context the parse-context location prefix from {@link ParseContext#location()}
   */
  public static void validate(
      @NonNull String envKey, @NonNull String value, @NonNull String context) {
    if (!isSecretRef(value)) {
      return; // a literal — anything goes.
    }
    String body = value.substring(SECRET_PREFIX.length());
    if (body.isBlank()) {
      throw new PipelineParseException(
          context
              + ": env key '"
              + envKey
              + "' has an empty secret reference — write 'secret:<credential-id>' "
              + "(e.g. 'secret:gh-token' or 'secret:aws/dev-key')");
    }
    if (body.indexOf(':') >= 0) {
      throw new PipelineParseException(
          context
              + ": env key '"
              + envKey
              + "' has a malformed secret reference '"
              + value
              + "' — the syntax is 'secret:<id>' with exactly one ':' "
              + "(the id may contain a '/' for scope/key but no further ':')");
    }
    // Whitespace inside the id is also a typo — credential ids never contain spaces.
    for (int i = 0; i < body.length(); i++) {
      char c = body.charAt(i);
      if (Character.isWhitespace(c)) {
        throw new PipelineParseException(
            context
                + ": env key '"
                + envKey
                + "' has a malformed secret reference '"
                + value
                + "' — credential ids must not contain whitespace");
      }
    }
  }
}
