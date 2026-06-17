package io.adaptiq.titan.trigger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The JSON ⇄ {@link Trigger} codec (design/50 D6).
 *
 * <p>Trigger <em>definitions</em> are persisted as a plain JSON array — a consumer stores the array
 * wherever its config lives (Titan: {@code titan.jobs.config_json}). The wire shape is {@code
 * {"type", "id", <type-specific…>}}.
 *
 * <p>The codec is <strong>pluggable</strong>: it never hard-codes a trigger type. {@link #write}
 * asks each {@link Trigger} to {@link Trigger#writeState write its own state}; {@link #read}
 * dispatches on the {@code type} discriminator through a registry of {@link TriggerReader}s — in
 * production, the registry is built from every registered {@link TriggerDescriptor}, so a
 * third-party trigger plugin round-trips with no change here (design/50 — extensible triggers).
 */
public final class TriggerCodec {

  private static final Logger LOGGER = Logger.getLogger(TriggerCodec.class.getName());

  private TriggerCodec() {}

  /** Reconstructs a trigger of one type from its persisted JSON object. */
  @FunctionalInterface
  public interface TriggerReader {
    @NonNull
    Trigger read(@CheckForNull String id, @NonNull JsonNode node);
  }

  /** Serialize trigger objects into a JSON array. Pure — each trigger writes its own state. */
  @NonNull
  public static ArrayNode write(@NonNull List<Trigger> triggers, @NonNull ObjectMapper mapper) {
    ArrayNode arr = mapper.createArrayNode();
    for (Trigger t : triggers) {
      ObjectNode node = mapper.createObjectNode();
      node.put("type", t.getType());
      node.put("id", t.getId());
      t.writeState(node);
      arr.add(node);
    }
    return arr;
  }

  /**
   * Deserialize a triggers array using an explicit {@code type → reader} registry — the pure form,
   * testable without a runtime container. A {@code null}/non-array node yields an empty list; an
   * entry whose {@code type} is not in the registry is skipped with a warning (a forward-compat
   * read of config written by a newer build or an uninstalled plugin).
   */
  @NonNull
  public static List<Trigger> read(
      @CheckForNull JsonNode triggersArray, @NonNull Map<String, TriggerReader> readers) {
    List<Trigger> out = new ArrayList<>();
    if (triggersArray == null || !triggersArray.isArray()) {
      return out;
    }
    for (JsonNode node : triggersArray) {
      String type = node.path("type").asText("");
      String id = node.hasNonNull("id") ? node.get("id").asText() : null;
      TriggerReader reader = readers.get(type);
      if (reader == null) {
        LOGGER.log(Level.WARNING, "[trigger] unknown trigger type ''{0}'' — skipped", type);
      } else {
        out.add(reader.read(id, node));
      }
    }
    return out;
  }

  /**
   * Deserialize a triggers array, resolving trigger types from the registered {@link
   * TriggerDescriptor}s — the production form. Requires the {@code ServiceLoader} registry to be
   * populated.
   */
  @NonNull
  public static List<Trigger> read(@CheckForNull JsonNode triggersArray) {
    return read(triggersArray, readersFromDescriptors());
  }

  /**
   * The {@code type → reader} registry projected from every registered {@link TriggerDescriptor}.
   */
  @NonNull
  public static Map<String, TriggerReader> readersFromDescriptors() {
    Map<String, TriggerReader> readers = new HashMap<>();
    for (TriggerDescriptor descriptor : TriggerDescriptor.all()) {
      readers.put(descriptor.triggerType(), descriptor::readState);
    }
    return readers;
  }
}
