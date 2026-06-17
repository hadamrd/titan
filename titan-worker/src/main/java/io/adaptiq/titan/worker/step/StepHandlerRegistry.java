package io.adaptiq.titan.worker.step;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The worker's table of known step types — {@code descriptorId → StepHandler} (Chunk 32A —
 * design/32 §3.3).
 *
 * <p>In 32A it holds the built-in handlers ({@code sh}, {@code script}) registered explicitly at
 * construction. Chunk 32D extends it: handler artifacts fetched on demand from the controller's
 * registry are registered here too, each in its own classloader. The lookup surface does not change
 * — that is the point of routing every step type through one registry now.
 *
 * <p>A duplicate {@code descriptorId} is a hard error, never a silent last-wins: two handlers
 * claiming {@code "sh"} is a misconfiguration the worker must refuse to start with.
 */
public final class StepHandlerRegistry {

  private final Map<String, StepHandler> handlers = new LinkedHashMap<>();

  /** Build a registry seeded with the given handlers (the built-ins). */
  public StepHandlerRegistry(Collection<StepHandler> initialHandlers) {
    for (StepHandler handler : initialHandlers) {
      register(handler);
    }
  }

  /**
   * Register a handler.
   *
   * @throws IllegalStateException if its {@code descriptorId} is already registered, or if it
   *     disagrees with its own descriptor
   */
  public void register(StepHandler handler) {
    String id = handler.descriptorId();
    if (id == null || id.isBlank()) {
      throw new IllegalStateException("step handler has a blank descriptorId: " + handler);
    }
    if (!id.equals(handler.descriptor().descriptorId())) {
      throw new IllegalStateException(
          "step handler '"
              + id
              + "' disagrees with its descriptor ('"
              + handler.descriptor().descriptorId()
              + "')");
    }
    StepHandler previous = handlers.putIfAbsent(id, handler);
    if (previous != null) {
      throw new IllegalStateException(
          "duplicate step descriptor '" + id + "': " + previous + " vs " + handler);
    }
  }

  /** Look up the handler for a step type. */
  public Optional<StepHandler> find(String descriptorId) {
    return Optional.ofNullable(handlers.get(descriptorId));
  }

  /** Every registered step type — for diagnostics and the "unknown step" error message. */
  public Set<String> descriptorIds() {
    return Set.copyOf(handlers.keySet());
  }
}
