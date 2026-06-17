package io.adaptiq.titan.flow.model;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A declared build parameter (design/29 §5 — {@code config_json} parameters, surfaced in the
 * pipeline definition as a {@code parameters:} block).
 *
 * <p>A pipeline declares the parameters it accepts; a build supplies values; the bake resolves the
 * two — applying defaults, validating required-ness and {@code choice} membership, coercing types —
 * into the effective {@code params} map that {@code when:} and {@code ${{ params.* }}} read.
 *
 * <p>Jackson-friendly: public no-arg constructor plus getters/setters.
 */
public class ParameterModel {

  /** Parameter name — the key under the {@code params} scope. */
  @NonNull private String name = "";

  /** {@code string} (default), {@code boolean}, {@code number} or {@code choice}. */
  @NonNull private String type = "string";

  /** Default value applied when a build does not supply one. {@code null} = no default. */
  @Nullable private Object defaultValue;

  /** Human-readable description (documentation / UI). */
  @Nullable private String description;

  /** Whether a build must supply this parameter (no default fallback). */
  private boolean required;

  /** Allowed values when {@code type == "choice"}. */
  @NonNull private List<String> choices = new ArrayList<>();

  /** Default constructor for Jackson deserialization. */
  public ParameterModel() {}

  @NonNull
  public String getName() {
    return name;
  }

  public void setName(@NonNull String name) {
    this.name = name;
  }

  @NonNull
  public String getType() {
    return type;
  }

  public void setType(@NonNull String type) {
    this.type = type;
  }

  @Nullable
  public Object getDefaultValue() {
    return defaultValue;
  }

  public void setDefaultValue(@Nullable Object defaultValue) {
    this.defaultValue = defaultValue;
  }

  @Nullable
  public String getDescription() {
    return description;
  }

  public void setDescription(@Nullable String description) {
    this.description = description;
  }

  public boolean isRequired() {
    return required;
  }

  public void setRequired(boolean required) {
    this.required = required;
  }

  @NonNull
  public List<String> getChoices() {
    return choices;
  }

  public void setChoices(@NonNull List<String> choices) {
    this.choices = choices;
  }
}
