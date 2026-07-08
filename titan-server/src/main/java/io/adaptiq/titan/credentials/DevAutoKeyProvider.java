package io.adaptiq.titan.credentials;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.crypto.CredentialKeyProvider;
import io.adaptiq.titan.flow.crypto.SecretCipher;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DEV ONLY — auto-generated KEK provider for the local rig (closes #800).
 *
 * <p><strong>Production-mode boot is untouched.</strong> This provider is inert (returns {@code
 * null} from {@link #credentialKey()}) unless the operator sets {@code
 * TITAN_CREDENTIALS_DEV_AUTO_KEK=true} (or the {@code titan.credentials.dev-auto-kek} system
 * property). Without that opt-in, the chain in {@link CredentialKeyProvider#active()} falls through
 * to the next provider exactly as before — which means a production deployment with no real KEK
 * still fails closed at the {@link DbEnvelopeBackend#activeKek()} guard. That fail-closed behaviour
 * is the load-bearing safety property; do not regress it.
 *
 * <p><strong>What it does when enabled.</strong> On first call:
 *
 * <ol>
 *   <li>looks for a key file at {@code TITAN_DEV_KEK_PATH} (default {@code
 *       /var/titan/secrets/dev-kek});
 *   <li>if present, base64-decodes and returns it (so {@code dev:down &amp;&amp; dev:titan} does
 *       not lose the key and thereby brick previously-encrypted credential rows);
 *   <li>if absent, generates a 256-bit random key via {@link SecureRandom}, writes it
 *       base64-encoded to that path with POSIX mode {@code 0600}, and logs a loud WARN banner with
 *       the full path.
 * </ol>
 *
 * <p>The production path is the Infisical-backed {@code
 * io.adaptiq.titan.keyprovider.infisical.InfisicalCredentialKeyProvider} (or any other KMS-backed
 * provider drop-in jar). This class exists strictly to remove the local-rig footgun where {@code
 * POST /api/v1/credentials} returned HTTP 500 with "no credential key configured" and blocked every
 * E2E discovery spec.
 */
@ApplicationScoped
@Typed(DevAutoKeyProvider.class)
public class DevAutoKeyProvider implements CredentialKeyProvider {

  static final String FLAG_ENV = "TITAN_CREDENTIALS_DEV_AUTO_KEK";
  static final String FLAG_SYSPROP = "titan.credentials.dev-auto-kek";

  /**
   * Newer, more explicit opt-in env (issue #1076 / V1-bar #5). Set to {@code 1} in dev to enable
   * the auto-KEK. Honoured ONLY when the profile is dev — see {@link #requireDevProfile()}.
   */
  static final String ALLOW_ENV = "LOOP_TITAN_ALLOW_DEV_KEK";

  static final String ALLOW_SYSPROP = "loop.titan.allow.dev.kek";
  static final String PATH_ENV = "TITAN_DEV_KEK_PATH";
  static final String PATH_SYSPROP = "titan.credentials.dev-auto-kek.path";
  static final String DEFAULT_PATH = "/var/titan/secrets/dev-kek";

  /**
   * Profile env / sysprop checked by {@link #requireDevProfile()}. The provider refuses to
   * construct unless the active profile is {@code dev}.
   */
  static final String PROFILE_ENV = "TITAN_PROFILE";

  static final String PROFILE_SYSPROP = "titan.profile";
  static final String QUARKUS_PROFILE_SYSPROP = "quarkus.profile";
  static final String DEV_PROFILE_VALUE = "dev";

  /**
   * Canonical error message thrown by the no-arg constructor when invoked outside the dev profile.
   * Exact wording is part of the V1-bar #5 contract (issue #1076 acceptance criteria) — do not
   * change without updating {@link DevAutoKeyProviderTest}.
   */
  static final String PROD_REFUSAL_MESSAGE =
      "DevAutoKeyProvider only valid in dev profile; configure a real KEK for prod"
          + " (see docs/operations/runbooks/kek-config.md).";

  private static final Logger LOGGER = Logger.getLogger(DevAutoKeyProvider.class.getName());

  private final boolean enabled;
  private final Path keyPath;
  private final SecureRandom random;
  private volatile byte[] cachedKey;

  /**
   * No-arg constructor used by CDI and {@link java.util.ServiceLoader}.
   *
   * @throws ConfigException if the active profile is not {@code dev}. This is the load-bearing
   *     fail-closed gate for V1-bar #5 — see {@link #requireDevProfile()}.
   */
  public DevAutoKeyProvider() {
    this(requireDevProfileAndReadFlag(), Path.of(readKeyPath()), new SecureRandom());
  }

  // Visible for tests. Bypasses the dev-profile guard — only the no-arg constructor is gated, so
  // that unit tests can directly construct an instance with explicit flag/path/random without
  // having to fiddle with the global JVM profile sysprop.
  DevAutoKeyProvider(boolean enabled, @NonNull Path keyPath, @NonNull SecureRandom random) {
    this.enabled = enabled;
    this.keyPath = keyPath;
    this.random = random;
  }

  @Override
  @Nullable
  public byte[] credentialKey() {
    if (!enabled) {
      // Disabled: stay inert so the chain falls through to a real provider (env / Infisical /
      // KMS), or — in production with no provider configured — to the fail-closed guard in
      // DbEnvelopeBackend. This is the production safety contract.
      return null;
    }
    byte[] key = cachedKey;
    if (key != null) {
      return key.clone();
    }
    synchronized (this) {
      if (cachedKey == null) {
        cachedKey = loadOrGenerate();
      }
      return cachedKey == null ? null : cachedKey.clone();
    }
  }

  @Override
  @NonNull
  public String describe() {
    if (!enabled) {
      return "dev-auto:disabled";
    }
    return "dev-auto:" + keyPath;
  }

  @Nullable
  private byte[] loadOrGenerate() {
    try {
      if (Files.exists(keyPath)) {
        String encoded = Files.readString(keyPath).strip();
        if (encoded.isEmpty()) {
          LOGGER.log(
              Level.WARNING, "[titan][dev-auto-kek] key file {0} is empty — regenerating", keyPath);
        } else {
          byte[] key = SecretCipher.decodeKey(encoded);
          LOGGER.log(
              Level.INFO,
              "[titan][dev-auto-kek] loaded existing DEV KEK from {0} — DEV ONLY",
              keyPath);
          return key;
        }
      }
      return generateAndPersist();
    } catch (IOException e) {
      LOGGER.log(
          Level.SEVERE, "[titan][dev-auto-kek] failed to load/generate DEV KEK at " + keyPath, e);
      return null;
    }
  }

  @NonNull
  private byte[] generateAndPersist() throws IOException {
    byte[] key = new byte[SecretCipher.KEY_LENGTH_BYTES];
    random.nextBytes(key);
    String encoded = Base64.getEncoder().encodeToString(key);

    Path parent = keyPath.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(
        keyPath,
        encoded,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    tryRestrictPermissions(keyPath);

    LOGGER.warning("====================================================================");
    LOGGER.warning("[titan][dev-auto-kek] *** DEV ONLY — DO NOT USE IN PRODUCTION ***");
    LOGGER.warning("[titan][dev-auto-kek] generated a new random AES-256 credential KEK");
    LOGGER.warning("[titan][dev-auto-kek] persisted to: " + keyPath.toAbsolutePath());
    LOGGER.warning("[titan][dev-auto-kek] enable flag: " + FLAG_ENV + "=true");
    LOGGER.warning("[titan][dev-auto-kek] production path: Infisical / KMS-backed provider jar");
    LOGGER.warning("====================================================================");
    return key;
  }

  private static void tryRestrictPermissions(@NonNull Path p) {
    try {
      Set<PosixFilePermission> perms =
          EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
      Files.setPosixFilePermissions(p, perms);
    } catch (UnsupportedOperationException | IOException ignored) {
      // Windows / non-POSIX FS — best-effort; this provider is dev-only and Linux-targeted.
      LOGGER.fine(
          "[titan][dev-auto-kek] could not set 0600 permissions on "
              + p
              + " (non-POSIX FS) — continuing");
    }
  }

  /**
   * Combines the dev-profile gate (#1076) with the legacy enabled-flag read. Called from the no-arg
   * constructor only; the package-private constructor used by tests bypasses the profile gate so
   * unit tests can drive both legs without leaking JVM-wide sysprops between cases.
   */
  private static boolean requireDevProfileAndReadFlag() {
    requireDevProfile();
    return readEnabledFlag();
  }

  /**
   * Fail-closed gate (#1076 / V1-bar #5). Throws {@link ConfigException} unless the active profile
   * is {@code dev}. The check is intentionally adversarial: any value other than the exact literal
   * {@code "dev"} (case-insensitive, trimmed) refuses construction. In particular, {@link
   * #ALLOW_ENV} being set is NOT sufficient — the profile is the gate, not the allow var.
   *
   * <p>Profile is resolved in this order:
   *
   * <ol>
   *   <li>{@link #PROFILE_ENV} env ({@code TITAN_PROFILE})
   *   <li>{@link #PROFILE_SYSPROP} sysprop ({@code titan.profile})
   *   <li>{@link #QUARKUS_PROFILE_SYSPROP} sysprop ({@code quarkus.profile}) — surfaces Quarkus'
   *       own profile (e.g. test runs that set {@code -Dquarkus.profile=dev})
   *   <li>{@link io.quarkus.runtime.LaunchMode#current()} — {@code DEVELOPMENT} / {@code TEST} both
   *       count as dev for this gate. Prod = {@code NORMAL}.
   * </ol>
   */
  static void requireDevProfile() {
    if (!isDevProfile()) {
      throw new ConfigException(PROD_REFUSAL_MESSAGE);
    }
  }

  /**
   * @return {@code true} if the active profile is {@code dev} or a test launch mode.
   */
  public static boolean isDevProfile() {
    String env = System.getenv(PROFILE_ENV);
    if (env != null && !env.isBlank()) {
      return DEV_PROFILE_VALUE.equalsIgnoreCase(env.trim());
    }
    String sys = System.getProperty(PROFILE_SYSPROP);
    if (sys != null && !sys.isBlank()) {
      return DEV_PROFILE_VALUE.equalsIgnoreCase(sys.trim());
    }
    String quarkus = System.getProperty(QUARKUS_PROFILE_SYSPROP);
    if (quarkus != null && !quarkus.isBlank()) {
      return DEV_PROFILE_VALUE.equalsIgnoreCase(quarkus.trim());
    }
    // Final fallback: Quarkus launch mode. TEST and DEVELOPMENT both count as dev — TEST so that
    // existing @QuarkusTest activation ITs (DevAutoKekIT) keep working without explicit profile
    // overrides; DEVELOPMENT so `task dev:titan` keeps working even if TITAN_PROFILE is not
    // explicitly set (it is, in rig/local/.env, but defence in depth).
    io.quarkus.runtime.LaunchMode mode = io.quarkus.runtime.LaunchMode.current();
    return mode == io.quarkus.runtime.LaunchMode.DEVELOPMENT
        || mode == io.quarkus.runtime.LaunchMode.TEST;
  }

  private static boolean readEnabledFlag() {
    // Honour both the legacy TITAN_CREDENTIALS_DEV_AUTO_KEK and the new LOOP_TITAN_ALLOW_DEV_KEK
    // env. The new var is the documented surface (#1076); the legacy var stays to keep existing
    // local-rig setups + the DevAutoKekIT sysprop wiring working.
    String allowEnv = System.getenv(ALLOW_ENV);
    if (isTruthy(allowEnv)) {
      return true;
    }
    String allowSys = System.getProperty(ALLOW_SYSPROP);
    if (isTruthy(allowSys)) {
      return true;
    }
    String env = System.getenv(FLAG_ENV);
    if (isTruthy(env)) {
      return true;
    }
    String sys = System.getProperty(FLAG_SYSPROP);
    return isTruthy(sys);
  }

  private static boolean isTruthy(@Nullable String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    String v = value.trim().toLowerCase(Locale.ROOT);
    return "1".equals(v) || "true".equals(v) || "yes".equals(v);
  }

  @NonNull
  private static String readKeyPath() {
    String env = System.getenv(PATH_ENV);
    if (env != null && !env.isBlank()) {
      return env.trim();
    }
    String sys = System.getProperty(PATH_SYSPROP);
    if (sys != null && !sys.isBlank()) {
      return sys.trim();
    }
    return DEFAULT_PATH;
  }
}
