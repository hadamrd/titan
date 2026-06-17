package io.adaptiq.titan.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * Request body for {@code POST /api/v1/jobs/{jobId}/builds} — trigger a new build.
 *
 * <p>All fields are optional; a bare {@code POST} with an empty body is valid.
 *
 * <p>Two parameter input shapes are accepted (closes #774). Callers should prefer the typed {@code
 * parameters} map; {@code parametersJson} is preserved for back-compat with the original webhook
 * and CLI callers.
 *
 * <ul>
 *   <li>{@code parameters}: typed map of parameter-name → value. The server validates each entry
 *       against the job's declared {@code parameters:} block (name must exist; declared {@code
 *       boolean} / {@code number} / {@code choice} types are coerce-checked) and serializes the
 *       result to the {@code parameters_json} column the bake reads. Wins when both are present.
 *   <li>{@code parametersJson}: raw JSON string — opaque to the API layer. Used as-is when {@code
 *       parameters} is absent.
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TriggerBuildRequest(
    /**
     * Typed parameter overrides (#774). Key = declared parameter name; value = primitive (string /
     * boolean / number) matching the declared type. Unknown parameter names → 400. Type mismatch →
     * 400.
     */
    Map<String, Object> parameters,
    /** Free-form JSON string of build parameters, e.g. {@code {"branch":"main"}}. May be null. */
    String parametersJson,
    /** Who or what triggered the build — free-form string (user login, webhook source). */
    String triggeredBy) {

  /** Back-compat ctor for callers that only supply the raw JSON shape (no typed overrides). */
  public TriggerBuildRequest(String parametersJson, String triggeredBy) {
    this(null, parametersJson, triggeredBy);
  }
}
