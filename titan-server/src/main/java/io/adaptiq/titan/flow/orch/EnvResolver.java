package io.adaptiq.titan.flow.orch;

import edu.umd.cs.findbugs.annotations.NonNull;
import io.adaptiq.titan.flow.parser.EnvValueRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Splits a merged env map into its literal entries and its {@code secret:<id>} references resolved
 * to plaintext (closes #1094 — PDL declarative {@code env:} with secret bindings).
 *
 * <p>The {@code env:} grammar (see {@code EnvScope}) accepts two kinds of value:
 *
 * <ul>
 *   <li><strong>Literal</strong> — any string ({@code LOG_LEVEL: info}). The value is passed
 *       through unchanged and rides in the task's <em>plaintext</em> payload (visible in the DB row
 *       for operators).
 *   <li><strong>Secret reference</strong> — {@code FOO: "secret:gh-token"}. The value is resolved
 *       at dispatch time, on the controller, against the encrypted credentials store. The resolved
 *       plaintext is folded into the per-step <em>sealed</em> credentials bundle (AES-256-GCM,
 *       design/39 §3.1) and added to the worker's mask-values list — it is never persisted in
 *       cleartext, never printed to the build log.
 * </ul>
 *
 * <p>Resolution is fail-fast: a {@code secret:} reference whose credential id is not in the store,
 * is blank, or unseal returns empty, throws a structured {@link EnvResolutionException} naming the
 * env variable AND the credential id. The orchestrator catches this at dispatch and fails the step
 * with {@code failure_category=CREDENTIAL} — the customer sees a clear reason, the worker never
 * runs with a half-populated env. This matches the failure model the existing {@code
 * CredentialResolver} uses for {@code credentials:} bindings (design/39 §5).
 *
 * <p>Id convention: a bare id like {@code gh-token} resolves under the {@code default} scope; an id
 * with a slash like {@code aws/dev-key} splits as {@code scope/key}. The id-parsing happens inside
 * the {@link PlaintextLookup} implementation (typically {@code CredentialsPort.resolveSecretRef}),
 * which already follows the same convention as a {@code credentials:} binding id.
 *
 * <p>Thread-safe — holds only the (assumed thread-safe) lookup function.
 */
public final class EnvResolver {

  private final PlaintextLookup lookup;

  /**
   * Construct a resolver against an arbitrary plaintext lookup. In production this is wired to
   * {@code CredentialsPort.resolveSecretRef}; tests inject a deterministic in-memory map without
   * the cipher / DAO machinery.
   */
  public EnvResolver(@NonNull PlaintextLookup lookup) {
    this.lookup = Objects.requireNonNull(lookup, "lookup");
  }

  /**
   * Split the {@code merged} env map (pipeline ← stage ← step, see {@code MergedEnv}) into:
   *
   * <ul>
   *   <li>{@link Split#plain plain} — literal values, kept in iteration order;
   *   <li>{@link Split#secret secret} — values that began with {@code secret:}, resolved to
   *       plaintext, also kept in iteration order;
   *   <li>{@link Split#maskValues maskValues} — every resolved secret value, in the order they were
   *       resolved, so the worker's masking log sink can redact them from the step log.
   * </ul>
   *
   * <p>The relative order of {@code plain} and {@code secret} entries within {@code merged} is
   * preserved across both output maps. {@code merged} may be empty — the result is then three empty
   * collections.
   *
   * @throws EnvResolutionException if a {@code secret:<id>} reference cannot be resolved
   */
  @NonNull
  public Split split(@NonNull Map<String, String> merged) {
    Objects.requireNonNull(merged, "merged");
    Map<String, String> plain = new LinkedHashMap<>();
    Map<String, String> secret = new LinkedHashMap<>();
    List<String> masks = new java.util.ArrayList<>();
    for (Map.Entry<String, String> entry : merged.entrySet()) {
      String envKey = entry.getKey();
      String value = entry.getValue();
      if (value == null) {
        // The parser rejects this, but be defensive — an absent value is treated as plain "".
        plain.put(envKey, "");
        continue;
      }
      if (!EnvValueRef.isSecretRef(value)) {
        plain.put(envKey, value);
        continue;
      }
      String id = EnvValueRef.secretId(value);
      Optional<String> resolved;
      try {
        resolved = lookup.resolve(id);
      } catch (RuntimeException e) {
        throw new EnvResolutionException(
            "env '"
                + envKey
                + "': could not resolve secret reference 'secret:"
                + id
                + "': "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
            e);
      }
      if (resolved.isEmpty()) {
        throw new EnvResolutionException(
            "env '"
                + envKey
                + "': secret reference 'secret:"
                + id
                + "' is not in the Titan credentials store");
      }
      String plaintext = resolved.get();
      if (plaintext.isEmpty()) {
        throw new EnvResolutionException(
            "env '"
                + envKey
                + "': secret reference 'secret:"
                + id
                + "' resolved to an empty value");
      }
      secret.put(envKey, plaintext);
      masks.add(plaintext);
    }
    return new Split(Map.copyOf(plain), Map.copyOf(secret), List.copyOf(masks));
  }

  /**
   * A pluggable plaintext lookup keyed by credential id ({@code "gh-token"} or {@code
   * "scope/key"}). Production wires this to {@code CredentialsPort.resolveSecretRef} which
   * delegates to the encrypted credentials store.
   */
  @FunctionalInterface
  public interface PlaintextLookup {
    @NonNull
    Optional<String> resolve(@NonNull String id);
  }

  /**
   * Outcome of {@link #split} — the literal entries (safe to ride in plaintext payload) and the
   * resolved secret entries (must enter the sealed credentials bundle + mask list).
   */
  public record Split(
      @NonNull Map<String, String> plain,
      @NonNull Map<String, String> secret,
      @NonNull List<String> maskValues) {

    public boolean hasSecrets() {
      return !secret.isEmpty();
    }
  }

  /**
   * Failure mode for an {@code env:} {@code secret:<id>} reference that cannot be resolved. The
   * orchestrator catches this at dispatch and fails the step with a structured {@code CREDENTIAL}
   * category — never an NPE or a generic {@code RuntimeException}, per the issue's adversarial test
   * matrix.
   */
  public static final class EnvResolutionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EnvResolutionException(@NonNull String message) {
      super(message);
    }

    public EnvResolutionException(@NonNull String message, @NonNull Throwable cause) {
      super(message, cause);
    }
  }
}
