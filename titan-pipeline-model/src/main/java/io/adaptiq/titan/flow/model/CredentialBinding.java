package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One declarative credential binding on a {@link StepModel} (design/39, implementing design/32 §12
 * D6).
 *
 * <p>Titan has no blocks, so a credential binding cannot be a wrapping construct (design/39 §1).
 * Instead a binding is a <em>property of a step</em>: it names a credential-store id and the shape
 * in which that credential is bound into the step's process — the env-variable name(s), or a
 * file-path variable. The binding's scope <em>is</em> the one step.
 *
 * <p>This model carries the credential <strong>id</strong> and the <strong>binding shape</strong>
 * only — never a secret value. Resolution against the credential store happens on the controller at
 * dispatch time (design/39 §3); the persisted {@code pipeline_model_json} is therefore safe — it
 * holds ids, not secrets.
 *
 * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
 */
public class CredentialBinding {

  /** The {@code usernamePassword} binding type — binds a username + password env pair. */
  public static final String TYPE_USERNAME_PASSWORD = "usernamePassword";

  /** The {@code string} binding type — binds a single secret-text env var. */
  public static final String TYPE_STRING = "string";

  /** The {@code file} binding type — binds an env var to the path of a temp file of bytes. */
  public static final String TYPE_FILE = "file";

  /** The {@code sshKey} binding type — binds an env var to the path of a temp private-key file. */
  public static final String TYPE_SSH_KEY = "sshKey";

  /** The credential-store id this binding resolves (design/32 §12 D6). */
  @NonNull private String id = "";

  /**
   * The binding type discriminator — {@link #TYPE_USERNAME_PASSWORD}, {@link #TYPE_STRING}, {@link
   * #TYPE_FILE} or {@link #TYPE_SSH_KEY}.
   */
  @NonNull private String type = "";

  /**
   * The type-specific binding keys — e.g. {@code usernameVariable}/{@code passwordVariable} for
   * {@code usernamePassword}, {@code variable} for {@code string}/{@code file}, {@code
   * keyFileVariable}/{@code usernameVariable}/{@code passphraseVariable} for {@code sshKey}. Kept
   * as a free-form map so a new credential type needs no model change — the resolver on the
   * controller interprets them per {@link #type}.
   */
  @NonNull private Map<String, String> bindings = new LinkedHashMap<>();

  /** Default constructor for Jackson deserialization. */
  public CredentialBinding() {}

  @NonNull
  public String getId() {
    return id;
  }

  public void setId(@NonNull String id) {
    this.id = id;
  }

  @NonNull
  public String getType() {
    return type;
  }

  public void setType(@NonNull String type) {
    this.type = type;
  }

  @NonNull
  public Map<String, String> getBindings() {
    return bindings;
  }

  public void setBindings(@NonNull Map<String, String> bindings) {
    this.bindings = bindings;
  }

  /** A single binding key's value, or {@code null} if absent. */
  @Nullable
  public String binding(@NonNull String key) {
    return bindings.get(key);
  }
}
