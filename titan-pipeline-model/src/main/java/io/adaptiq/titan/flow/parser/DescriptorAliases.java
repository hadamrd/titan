package io.adaptiq.titan.flow.parser;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * Ergonomic descriptor-key aliases for the YAML PDL — purely a parse-time rewrite. A YAML key
 * appearing here is collapsed to its canonical descriptor before the {@link
 * io.adaptiq.titan.flow.model.StepModel} is built, so the rest of the engine — JSON schema, step
 * scopes, worker dispatch — never sees the alias.
 *
 * <p>The alias is intentionally invisible to {@link
 * io.adaptiq.titan.flow.parser.grammar.TitanGrammar} (and therefore to the generated JSON schema):
 * the grammar still declares the single canonical keyword, keeping {@code
 * GrammarSchemaContractTest} / {@code TitanSchemaGenerationTest} stable.
 *
 * <p>Current aliases:
 *
 * <ul>
 *   <li>{@code wait} &rarr; {@code sleep} (closes #706). #704 confirmed {@code sleep:} is a
 *       controller-native durable parked node; a worker-side wait step would be strictly inferior.
 *       {@code wait:} is the ergonomic spelling for users with muscle memory from other CI DSLs.
 * </ul>
 */
final class DescriptorAliases {

  private static final Map<String, String> ALIASES = Map.of("wait", "sleep");

  private DescriptorAliases() {}

  /**
   * Return the canonical descriptor key for the given YAML descriptor key — the key itself when no
   * alias is registered.
   */
  @NonNull
  static String canonicalize(@NonNull String descriptorKey) {
    return ALIASES.getOrDefault(descriptorKey, descriptorKey);
  }
}
