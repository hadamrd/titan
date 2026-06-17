package io.adaptiq.titan.credentials;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.CredentialsPort;
import io.adaptiq.titan.flow.model.CredentialBinding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves a step's declarative {@link CredentialBinding}s against the Titan {@link
 * CredentialsService}.
 *
 * <p>D6 is unchanged from design/39 §1: a Titan {@code withCredentials} is <strong>not</strong> a
 * wrapping step; it is a per-step declarative property. The secret is resolved here, on the
 * controller, at the moment the orchestrator builds the step's {@code EXECUTE_COMMAND} payload. The
 * worker never sees the credentials store, never sees an id it could resolve, and has no network
 * path to the controller's secrets (design/26 Tier C).
 *
 * <p>The credential id of a {@link CredentialBinding} is interpreted as a {@code "scope/key"}
 * tuple, addressing one row in {@code titan.credentials}. The plaintext returned by {@link
 * CredentialsService#resolvePlaintext} is decoded per kind:
 *
 * <ul>
 *   <li>{@code STRING} — the plaintext is the secret value.
 *   <li>{@code USERNAME_PASSWORD} — the plaintext is a JSON object {@code {username, password}}.
 *   <li>{@code FILE} — the plaintext is a base64-encoded blob of file bytes.
 *   <li>{@code SSH_KEY} — the plaintext is a JSON object {@code {privateKey, passphrase}}.
 * </ul>
 *
 * <p>Resolution produces a {@link CredentialsPort.Resolved} bundle: env entries to merge into the
 * payload, the raw secret strings the worker masks in the step log, and any secret <em>files</em>
 * (a {@code file} or {@code sshKey} binding) the worker materialises into its per-task workspace. A
 * missing credential, a wrong type or an empty secret throws {@link CredentialResolutionException}
 * — the orchestrator fails the step at dispatch (design/39 §5), because the worker cannot.
 */
public final class CredentialResolver implements CredentialsPort {

  private static final Logger LOGGER = Logger.getLogger(CredentialResolver.class.getName());
  private static final ObjectMapper JSON = new ObjectMapper();

  private final CredentialsService credentials;

  public CredentialResolver(@NonNull CredentialsService credentials) {
    this.credentials = credentials;
  }

  /**
   * Alias for {@link CredentialsPort.CredentialResolutionException} — the interface-level exception
   * type the {@code TitanOrchestrator} catches at dispatch time. Re-exported here so call sites
   * that referenced {@code CredentialResolver.CredentialResolutionException} (the Wave 2b
   * plugin-side class) keep compiling.
   */
  public static final class CredentialResolutionException
      extends CredentialsPort.CredentialResolutionException {
    public CredentialResolutionException(@NonNull String message) {
      super(message);
    }
  }

  @Override
  @NonNull
  public Resolved resolve(
      @NonNull List<CredentialBinding> bindings, @NonNull List<String> sshAgentIds) {
    if (bindings.isEmpty() && sshAgentIds.isEmpty()) {
      return EMPTY;
    }
    Map<String, String> env = new LinkedHashMap<>();
    List<String> maskValues = new ArrayList<>();
    List<SecretFile> files = new ArrayList<>();
    for (CredentialBinding binding : bindings) {
      resolveOne(binding, env, maskValues, files);
    }
    List<SshAgentKey> sshAgentKeys = resolveSshAgent(sshAgentIds, maskValues);
    return new Resolved(env, maskValues, files, sshAgentKeys);
  }

  /**
   * Resolve a {@code secret:<id>} env-value reference (closes #1094) to its plaintext, or empty if
   * not in the store. Uses the same {@code scope/key} parse rule as {@code credentials:} bindings
   * (see {@link #parseId}). A bare id like {@code "gh-token"} resolves under the {@code "default"}
   * scope.
   */
  @Override
  @NonNull
  public Optional<String> resolveSecretRef(@NonNull String id) {
    ScopedKey ref = parseId(id);
    return credentials.resolvePlaintext(ref.scope, ref.key);
  }

  /**
   * Resolve a list of {@code sshAgent:} credential ids (design/41 §3) into the SSH keys the worker
   * loads into a transient {@code ssh-agent}. Each id must name an {@link Credential#KIND_SSH_KEY}
   * credential; a missing id, a wrong type, or a credential with no private-key material throws
   * {@link CredentialResolutionException}, so the orchestrator fails the step at dispatch.
   *
   * @param ids the {@code sshAgent:} credential ids, in declaration order
   * @return one {@link SshAgentKey} per id
   * @throws CredentialResolutionException if any id cannot be resolved
   */
  @NonNull
  public List<SshAgentKey> resolveSshAgent(@NonNull List<String> ids) {
    return resolveSshAgent(ids, new ArrayList<>());
  }

  /**
   * As {@link #resolveSshAgent(List)}, also appending each private key and each non-blank
   * passphrase to {@code maskValues} so they are masked in the step log (design/41 §3).
   */
  @NonNull
  private List<SshAgentKey> resolveSshAgent(
      @NonNull List<String> ids, @NonNull List<String> maskValues) {
    if (ids.isEmpty()) {
      return List.of();
    }
    List<SshAgentKey> keys = new ArrayList<>();
    for (String id : ids) {
      ScopedKey ref = parseId(id);
      Credential credential = lookupOrThrow(id);
      if (!Credential.KIND_SSH_KEY.equals(credential.kind())) {
        throw new CredentialResolutionException(
            "sshAgent credential '"
                + id
                + "': expected an SSH private key credential, "
                + "but the stored credential is a "
                + credential.kind());
      }
      String plain = unsealOrThrow(ref, id);
      SshKeyMaterial mat = parseSshKey(id, plain);
      addPrintableMask(maskValues, mat.privateKey.getBytes(StandardCharsets.UTF_8));
      if (mat.passphrase != null) {
        maskValues.add(mat.passphrase);
      }
      keys.add(new SshAgentKey(mat.privateKey, mat.passphrase));
    }
    return keys;
  }

  private void resolveOne(
      @NonNull CredentialBinding binding,
      @NonNull Map<String, String> env,
      @NonNull List<String> maskValues,
      @NonNull List<SecretFile> files) {
    String id = binding.getId();
    Credential credential = lookupOrThrow(id);
    switch (binding.getType()) {
      case CredentialBinding.TYPE_USERNAME_PASSWORD ->
          resolveUsernamePassword(binding, credential, env, maskValues);
      case CredentialBinding.TYPE_STRING -> resolveString(binding, credential, env, maskValues);
      case CredentialBinding.TYPE_FILE -> resolveFile(binding, credential, env, maskValues, files);
      case CredentialBinding.TYPE_SSH_KEY ->
          resolveSshKey(binding, credential, env, maskValues, files);
      default ->
          throw new CredentialResolutionException(
              "credential '" + id + "': unsupported binding type '" + binding.getType() + "'");
    }
  }

  private void resolveUsernamePassword(
      @NonNull CredentialBinding binding,
      @NonNull Credential credential,
      @NonNull Map<String, String> env,
      @NonNull List<String> maskValues) {
    if (!Credential.KIND_USERNAME_PASSWORD.equals(credential.kind())) {
      throw typeMismatch(binding, credential, "username/password");
    }
    String userVar = requireBinding(binding, "usernameVariable");
    String passVar = requireBinding(binding, "passwordVariable");
    String plain = unsealOrThrow(parseId(binding.getId()), binding.getId());
    UsernamePassword up = parseUsernamePassword(binding.getId(), plain);
    requireNonBlank(binding, "password", up.password);
    env.put(userVar, up.username == null ? "" : up.username);
    env.put(passVar, up.password);
    // The username is not a secret; the password is — mask only the password.
    maskValues.add(up.password);
  }

  private void resolveString(
      @NonNull CredentialBinding binding,
      @NonNull Credential credential,
      @NonNull Map<String, String> env,
      @NonNull List<String> maskValues) {
    if (!Credential.KIND_STRING.equals(credential.kind())) {
      throw typeMismatch(binding, credential, "secret text");
    }
    String var = requireBinding(binding, "variable");
    String secret = unsealOrThrow(parseId(binding.getId()), binding.getId());
    requireNonBlank(binding, "secret text", secret);
    env.put(var, secret);
    maskValues.add(secret);
  }

  private void resolveFile(
      @NonNull CredentialBinding binding,
      @NonNull Credential credential,
      @NonNull Map<String, String> env,
      @NonNull List<String> maskValues,
      @NonNull List<SecretFile> files) {
    if (!Credential.KIND_FILE.equals(credential.kind())) {
      throw typeMismatch(binding, credential, "secret file");
    }
    String var = requireBinding(binding, "variable");
    String base64 = unsealOrThrow(parseId(binding.getId()), binding.getId());
    byte[] content;
    try {
      content = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw new CredentialResolutionException(
          "credential '" + binding.getId() + "': file content is not valid base64");
    }
    files.add(
        new SecretFile(
            var, safeFileName(binding.getId(), null), Base64.getEncoder().encodeToString(content)));
    // Best-effort: if the file is text-shaped, mask its content too.
    addPrintableMask(maskValues, content);
  }

  private void resolveSshKey(
      @NonNull CredentialBinding binding,
      @NonNull Credential credential,
      @NonNull Map<String, String> env,
      @NonNull List<String> maskValues,
      @NonNull List<SecretFile> files) {
    if (!Credential.KIND_SSH_KEY.equals(credential.kind())) {
      throw typeMismatch(binding, credential, "SSH private key");
    }
    String keyFileVar = requireBinding(binding, "keyFileVariable");
    String plain = unsealOrThrow(parseId(binding.getId()), binding.getId());
    SshKeyMaterial mat = parseSshKey(binding.getId(), plain);
    byte[] keyBytes = mat.privateKey.getBytes(StandardCharsets.UTF_8);
    files.add(
        new SecretFile(
            keyFileVar,
            safeFileName(binding.getId(), "ssh-key"),
            Base64.getEncoder().encodeToString(keyBytes)));
    addPrintableMask(maskValues, keyBytes);

    String usernameVar = binding.binding("usernameVariable");
    if (usernameVar != null) {
      env.put(usernameVar, mat.username == null ? "" : mat.username);
    }
    String passphraseVar = binding.binding("passphraseVariable");
    if (passphraseVar != null && mat.passphrase != null) {
      env.put(passphraseVar, mat.passphrase);
      if (!mat.passphrase.isEmpty()) {
        maskValues.add(mat.passphrase);
      }
    }
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  /**
   * Parse a binding id of the form {@code "scope/key"} into its addressing tuple. A bare id (no
   * slash) is taken as a {@code key} under the conventional {@code "default"} scope.
   */
  @NonNull
  private static ScopedKey parseId(@NonNull String id) {
    int slash = id.indexOf('/');
    if (slash < 0) {
      return new ScopedKey("default", id);
    }
    return new ScopedKey(id.substring(0, slash), id.substring(slash + 1));
  }

  @NonNull
  private Credential lookupOrThrow(@NonNull String id) {
    ScopedKey ref = parseId(id);
    Optional<Credential> found = credentials.findByScopeAndKey(ref.scope, ref.key);
    if (found.isEmpty()) {
      throw new CredentialResolutionException(
          "credential '" + id + "' not found in the Titan credentials store");
    }
    return found.get();
  }

  @NonNull
  private String unsealOrThrow(@NonNull ScopedKey ref, @NonNull String id) {
    Optional<String> plain = credentials.resolvePlaintext(ref.scope, ref.key);
    if (plain.isEmpty()) {
      throw new CredentialResolutionException("credential '" + id + "' unseal returned empty");
    }
    return plain.get();
  }

  @NonNull
  private static String requireBinding(@NonNull CredentialBinding binding, @NonNull String key) {
    String value = binding.binding(key);
    if (value == null || value.isBlank()) {
      throw new CredentialResolutionException(
          "credential '"
              + binding.getId()
              + "' ("
              + binding.getType()
              + "): missing required binding key '"
              + key
              + "'");
    }
    return value;
  }

  private static void requireNonBlank(
      @NonNull CredentialBinding binding, @NonNull String what, @Nullable String value) {
    if (value == null || value.isEmpty()) {
      throw new CredentialResolutionException(
          "credential '" + binding.getId() + "': resolved " + what + " is empty");
    }
  }

  @NonNull
  private static CredentialResolutionException typeMismatch(
      @NonNull CredentialBinding binding,
      @NonNull Credential credential,
      @NonNull String expected) {
    return new CredentialResolutionException(
        "credential '"
            + binding.getId()
            + "': binding type '"
            + binding.getType()
            + "' expects a "
            + expected
            + " credential, but the stored credential is a "
            + credential.kind());
  }

  /**
   * If {@code content} is printable text, add it as a mask value — best-effort, so a secret file
   * whose content is a token does not leak when a step echoes it. Binary content is not masked (it
   * would not appear as text in a log anyway).
   */
  private static void addPrintableMask(@NonNull List<String> maskValues, @NonNull byte[] content) {
    if (content.length == 0 || content.length > 64 * 1024) {
      return;
    }
    for (byte b : content) {
      int c = b & 0xff;
      if (c != '\n' && c != '\r' && c != '\t' && (c < 0x20 || c > 0x7e)) {
        return; // not printable ASCII — skip
      }
    }
    String text = new String(content, StandardCharsets.UTF_8).strip();
    if (!text.isEmpty()) {
      maskValues.add(text);
    }
  }

  /** A stable, filesystem-safe temp-file name derived from the credential id. */
  @NonNull
  private static String safeFileName(@NonNull String id, @Nullable String original) {
    String base = id.replaceAll("[^A-Za-z0-9._-]+", "_");
    if (base.isBlank()) {
      base = "credential";
    }
    if (original != null && !original.isBlank()) {
      String ext = original.replaceAll("[^A-Za-z0-9._-]+", "_");
      return base + "-" + ext;
    }
    return base;
  }

  @NonNull
  private static UsernamePassword parseUsernamePassword(@NonNull String id, @NonNull String plain) {
    try {
      JsonNode root = JSON.readTree(plain);
      String u = root.path("username").asText(null);
      String p = root.path("password").asText(null);
      return new UsernamePassword(u, p == null ? "" : p);
    } catch (IOException e) {
      throw new CredentialResolutionException(
          "credential '" + id + "': USERNAME_PASSWORD payload is not valid JSON");
    }
  }

  @NonNull
  private static SshKeyMaterial parseSshKey(@NonNull String id, @NonNull String plain) {
    try {
      JsonNode root = JSON.readTree(plain);
      String pk = root.path("privateKey").asText(null);
      if (pk == null || pk.isEmpty()) {
        throw new CredentialResolutionException(
            "credential '" + id + "': SSH key has no private key material");
      }
      String passphrase = root.path("passphrase").asText(null);
      if (passphrase != null && passphrase.isEmpty()) {
        passphrase = null;
      }
      String username = root.path("username").asText(null);
      return new SshKeyMaterial(pk, passphrase, username);
    } catch (IOException e) {
      throw new CredentialResolutionException(
          "credential '" + id + "': SSH_KEY payload is not valid JSON");
    }
  }

  private record ScopedKey(@NonNull String scope, @NonNull String key) {}

  private record UsernamePassword(@Nullable String username, @NonNull String password) {}

  private record SshKeyMaterial(
      @NonNull String privateKey, @Nullable String passphrase, @Nullable String username) {}

  static {
    LOGGER.log(Level.FINE, "[titan] CredentialResolver loaded");
  }
}
