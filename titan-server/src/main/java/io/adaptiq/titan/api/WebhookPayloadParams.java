package io.adaptiq.titan.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Extracts trigger-supplied build parameters from a GitHub webhook payload (issue #938).
 *
 * <p>GitHub Actions models trigger-supplied inputs in two ways:
 *
 * <ul>
 *   <li><b>{@code repository_dispatch}.{@code client_payload}</b> — a free-form JSON object the API
 *       caller sends with {@code POST /repos/{owner}/{repo}/dispatches}. Each scalar key/value is
 *       made available to the workflow as an input. Titan mirrors this contract.
 *   <li><b>{@code workflow_dispatch}.{@code inputs}</b> — UI-driven manual run. Not delivered as a
 *       webhook today; intentionally out of scope.
 * </ul>
 *
 * <p>Only JSON scalars (text / number / boolean) are extracted; nested objects and arrays are
 * dropped — the param surface here is "string-shaped scalars", because that is what the existing
 * parameter-bake (declared {@code parameters:}) consumes. Type coercion belongs to the PDL
 * parameter step, not the webhook layer.
 *
 * <p>Malformed input never throws: missing field → empty map; non-object {@code client_payload} →
 * empty map; non-scalar values are skipped individually. The caller's behaviour for an empty
 * supplied set is well-defined (matcher reports any required-no-default params as missing).
 */
public final class WebhookPayloadParams {

  private static final Logger LOGGER = Logger.getLogger(WebhookPayloadParams.class.getName());
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private WebhookPayloadParams() {}

  /**
   * Extract the supplied parameter map for a webhook delivery.
   *
   * @param payload the parsed JSON body of the delivery, or {@code null}.
   * @param event the {@code X-GitHub-Event} header value (case-insensitive). Only {@code
   *     repository_dispatch} contributes today; everything else returns an empty map.
   * @return ordered (insertion-preserving) map of param name → string-rendered scalar value. Empty
   *     when nothing was supplied or the payload is malformed.
   */
  @NonNull
  public static Map<String, String> extract(@Nullable JsonNode payload, @Nullable String event) {
    if (payload == null || event == null) {
      return Map.of();
    }
    if (!"repository_dispatch".equalsIgnoreCase(event)) {
      return Map.of();
    }
    JsonNode cp = payload.get("client_payload");
    if (cp == null || cp.isNull() || !cp.isObject()) {
      // Missing or malformed — silently empty. GitHub's API allows `client_payload` to be
      // entirely absent; that is a valid "no inputs" dispatch.
      if (cp != null && !cp.isNull() && !cp.isObject()) {
        LOGGER.log(
            Level.FINE,
            "[webhook] repository_dispatch.client_payload is not a JSON object (was {0}) —"
                + " ignoring",
            new Object[] {cp.getNodeType()});
      }
      return Map.of();
    }
    Map<String, String> out = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = cp.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      String key = e.getKey();
      JsonNode v = e.getValue();
      if (key == null || key.isEmpty() || v == null || v.isNull()) {
        continue;
      }
      // Only scalars are surfaced as params (string / number / boolean). Nested objects and
      // arrays are silently dropped — Titan's parameter surface is scalar-shaped.
      if (v.isTextual()) {
        out.put(key, v.asText());
      } else if (v.isNumber() || v.isBoolean()) {
        out.put(key, v.asText());
      }
      // else: object / array → drop.
    }
    return out;
  }

  /**
   * Same as {@link #extract} but returns only the key set — handy for the matcher's
   * supplied-name-set argument.
   */
  @NonNull
  public static Set<String> suppliedKeys(@Nullable JsonNode payload, @Nullable String event) {
    return extract(payload, event).keySet();
  }

  /**
   * Serialise a supplied-param map to the JSON shape stored in {@code builds.parameters_json}.
   * Returns {@code null} when the map is empty — keeps {@code parameters_json} unset for builds
   * that received no payload inputs, matching the pre-#938 manual-trigger shape.
   */
  @Nullable
  public static String toParametersJson(@NonNull Map<String, String> supplied) {
    if (supplied.isEmpty()) {
      return null;
    }
    try {
      return MAPPER.writeValueAsString(supplied);
    } catch (JsonProcessingException e) {
      // The map is String→String; Jackson cannot fail here. Log + null so the build still
      // enqueues (with no params) rather than 500'ing the webhook.
      LOGGER.log(
          Level.WARNING,
          "[webhook] could not serialise supplied params (this should be unreachable): {0}",
          new Object[] {e.getMessage()});
      return null;
    }
  }
}
