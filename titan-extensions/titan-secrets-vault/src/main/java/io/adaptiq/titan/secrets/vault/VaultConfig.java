package io.adaptiq.titan.secrets.vault;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Configuration for the Vault backend.
 *
 * <p>Read from the process environment by {@link #fromEnv()}; the {@link VaultSecretsBackend}
 * no-arg ctor calls that. Tests can construct an instance directly via {@link #appRole(String,
 * String, String, String, String)}, {@link #token(String, String, String, String)}, or {@link
 * #kubernetes(String, String, String, String, String)} to inject a Testcontainer URL.
 *
 * <p>{@link #mount()} defaults to {@code secret} (the dev-mode default KV v2 mount). {@link
 * #namespace()} is {@code null} unless this is Vault Enterprise.
 *
 * <h2>Auth modes</h2>
 *
 * <p>Three auth modes are supported, selected via {@link #authMode()}:
 *
 * <ul>
 *   <li>{@link AuthMode#APPROLE} — {@code roleId} + {@code secretId} are set; client logs in via
 *       {@code /v1/auth/approle/login} to obtain a TTL'd token.
 *   <li>{@link AuthMode#TOKEN} — {@code vaultToken} is set; client uses it directly. No login
 *       round-trip; no refresh (the user owns token rotation).
 *   <li>{@link AuthMode#KUBERNETES} — {@code vaultRole} is set and the SA JWT is readable from
 *       {@code kubernetesSaTokenPath} (default {@code
 *       /var/run/secrets/kubernetes.io/serviceaccount/token}). Client POSTs the JWT to {@code
 *       /v1/auth/kubernetes/login} to obtain a TTL'd token; the SA token is re-read on every login
 *       so projected-volume rotation is picked up.
 * </ul>
 *
 * <p>Exactly one of the three credential bundles MUST be set. Setting more than one or none is a
 * config error — discriminated, no string sniffing (CONSTITUTION).
 *
 * <p><strong>Secret hygiene.</strong> None of {@code roleId} / {@code secretId} / {@code
 * vaultToken} is ever {@code toString}'d here — the record's auto-generated {@code toString} would
 * expose them. We override it to print only non-sensitive fields. Code that needs to log this
 * config: use the override.
 */
public record VaultConfig(
    @NonNull String addr,
    @Nullable String roleId,
    @Nullable String secretId,
    @Nullable String vaultToken,
    @Nullable String vaultRole,
    @Nullable String kubernetesSaTokenPath,
    @NonNull String mount,
    @Nullable String namespace) {

  /** Default KV v2 mount path in Vault dev mode and most installs. */
  public static final String DEFAULT_MOUNT = "secret";

  /** Default SA-token mount path inside a kubernetes pod. */
  public static final String DEFAULT_K8S_SA_TOKEN_PATH =
      "/var/run/secrets/kubernetes.io/serviceaccount/token";

  /** Discriminator for the auth flow {@link VaultHttpClient} uses. */
  public enum AuthMode {
    APPROLE,
    TOKEN,
    KUBERNETES
  }

  public VaultConfig {
    Objects.requireNonNull(addr, "addr");
    Objects.requireNonNull(mount, "mount");
    if (addr.isBlank()) {
      throw new IllegalArgumentException("VAULT_ADDR must not be blank");
    }
    boolean hasApprole =
        roleId != null && !roleId.isBlank() && secretId != null && !secretId.isBlank();
    boolean hasPartialApprole =
        (roleId != null && !roleId.isBlank()) || (secretId != null && !secretId.isBlank());
    boolean hasToken = vaultToken != null && !vaultToken.isBlank();
    boolean hasKubernetes = vaultRole != null && !vaultRole.isBlank();

    int modes = 0;
    if (hasApprole) {
      modes++;
    }
    if (hasToken) {
      modes++;
    }
    if (hasKubernetes) {
      modes++;
    }
    if (modes > 1) {
      throw new IllegalArgumentException(
          "VaultConfig: AppRole (roleId/secretId), token (vaultToken), and kubernetes (vaultRole)"
              + " auth modes are mutually exclusive — set exactly one auth mode");
    }
    if (modes == 0) {
      if (hasPartialApprole) {
        throw new IllegalArgumentException(
            "VaultConfig: AppRole requires BOTH roleId and secretId");
      }
      throw new IllegalArgumentException(
          "VaultConfig: no auth credentials — set AppRole (roleId+secretId), vaultToken, or"
              + " kubernetes (vaultRole)");
    }
    if (hasKubernetes) {
      String resolvedPath =
          (kubernetesSaTokenPath == null || kubernetesSaTokenPath.isBlank())
              ? DEFAULT_K8S_SA_TOKEN_PATH
              : kubernetesSaTokenPath;
      // Validate at construction so the operator gets a clear error on startup rather than at
      // the first resolve. Readability is checked, not contents — contents may rotate later.
      Path p = Path.of(resolvedPath);
      if (!Files.isReadable(p)) {
        throw new IllegalArgumentException(
            "VaultConfig: kubernetes auth mode requires a readable SA token file at "
                + resolvedPath
                + " — either run in-cluster, mount a projected SA token, or set"
                + " VAULT_K8S_SA_TOKEN_PATH to a test fixture");
      }
    }
  }

  /** Discriminator method — replaces nullable-field sniffing at call sites. */
  @NonNull
  public AuthMode authMode() {
    if (vaultToken != null && !vaultToken.isBlank()) {
      return AuthMode.TOKEN;
    }
    if (vaultRole != null && !vaultRole.isBlank()) {
      return AuthMode.KUBERNETES;
    }
    return AuthMode.APPROLE;
  }

  /**
   * Resolved SA-token path for kubernetes mode. Falls back to {@link #DEFAULT_K8S_SA_TOKEN_PATH}
   * when {@link #kubernetesSaTokenPath()} is null/blank. Only meaningful when {@link #authMode()}
   * is {@link AuthMode#KUBERNETES}.
   */
  @NonNull
  public String resolvedKubernetesSaTokenPath() {
    if (kubernetesSaTokenPath == null || kubernetesSaTokenPath.isBlank()) {
      return DEFAULT_K8S_SA_TOKEN_PATH;
    }
    return kubernetesSaTokenPath;
  }

  /** Factory for tests / explicit wiring — AppRole auth. */
  @NonNull
  public static VaultConfig appRole(
      @NonNull String addr,
      @NonNull String roleId,
      @NonNull String secretId,
      @NonNull String mount,
      @Nullable String namespace) {
    return new VaultConfig(addr, roleId, secretId, null, null, null, mount, namespace);
  }

  /** Factory for tests / explicit wiring — direct token auth. */
  @NonNull
  public static VaultConfig token(
      @NonNull String addr,
      @NonNull String vaultToken,
      @NonNull String mount,
      @Nullable String namespace) {
    return new VaultConfig(addr, null, null, vaultToken, null, null, mount, namespace);
  }

  /**
   * Factory for tests / explicit wiring — kubernetes service-account auth. {@code
   * kubernetesSaTokenPath} may be null to use {@link #DEFAULT_K8S_SA_TOKEN_PATH}.
   */
  @NonNull
  public static VaultConfig kubernetes(
      @NonNull String addr,
      @NonNull String vaultRole,
      @Nullable String kubernetesSaTokenPath,
      @NonNull String mount,
      @Nullable String namespace) {
    return new VaultConfig(
        addr, null, null, null, vaultRole, kubernetesSaTokenPath, mount, namespace);
  }

  /**
   * Read from environment. Returns {@code null} if {@code VAULT_ADDR} is unset, OR if none of the
   * auth modes is fully populated — the backend treats that as "not configured" and surfaces a
   * clear error on first resolve, rather than crashing at construction time and taking the whole
   * controller down.
   *
   * <p>Auth-mode selection precedence: {@code VAULT_TOKEN} wins over both {@code VAULT_ROLE}
   * (kubernetes) and the AppRole pair; {@code VAULT_ROLE} wins over AppRole. Operator-override
   * semantics — interactive env vars beat baked-in service config.
   */
  @Nullable
  public static VaultConfig fromEnv() {
    String addr = System.getenv("VAULT_ADDR");
    String roleId = System.getenv("VAULT_ROLE_ID");
    String secretId = System.getenv("VAULT_SECRET_ID");
    String vaultToken = System.getenv("VAULT_TOKEN");
    String vaultRole = System.getenv("VAULT_ROLE");
    String saTokenPath = System.getenv("VAULT_K8S_SA_TOKEN_PATH");
    String namespace = System.getenv("VAULT_NAMESPACE");
    String mount = System.getenv("VAULT_KV_MOUNT");
    if (addr == null || addr.isBlank()) {
      return null;
    }
    String resolvedMount = (mount == null || mount.isBlank()) ? DEFAULT_MOUNT : mount;
    String resolvedNs = (namespace == null || namespace.isBlank()) ? null : namespace;
    boolean hasToken = vaultToken != null && !vaultToken.isBlank();
    boolean hasKubernetes = vaultRole != null && !vaultRole.isBlank();
    boolean hasApprole =
        roleId != null && !roleId.isBlank() && secretId != null && !secretId.isBlank();
    if (hasToken) {
      // Prefer token if set — operator override semantics.
      return new VaultConfig(addr, null, null, vaultToken, null, null, resolvedMount, resolvedNs);
    }
    if (hasKubernetes) {
      return new VaultConfig(
          addr, null, null, null, vaultRole, saTokenPath, resolvedMount, resolvedNs);
    }
    if (hasApprole) {
      return new VaultConfig(addr, roleId, secretId, null, null, null, resolvedMount, resolvedNs);
    }
    return null;
  }

  /**
   * Override of the record's default toString to keep credentials out of logs / heap dumps that
   * stringify config objects. {@code roleId} / {@code secretId} / {@code vaultToken} are replaced
   * with {@code "<redacted>"} (or {@code "<none>"} when unset). {@code vaultRole} is NOT redacted —
   * it's a role name (analogous to a username), not a secret.
   */
  @Override
  public String toString() {
    return "VaultConfig[addr="
        + addr
        + ", authMode="
        + authMode()
        + ", roleId="
        + (roleId == null ? "<none>" : "<redacted>")
        + ", secretId="
        + (secretId == null ? "<none>" : "<redacted>")
        + ", vaultToken="
        + (vaultToken == null ? "<none>" : "<redacted>")
        + ", vaultRole="
        + (vaultRole == null ? "<none>" : vaultRole)
        + ", kubernetesSaTokenPath="
        + (kubernetesSaTokenPath == null ? "<default>" : kubernetesSaTokenPath)
        + ", mount="
        + mount
        + ", namespace="
        + (namespace == null ? "<none>" : namespace)
        + "]";
  }
}
