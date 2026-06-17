package io.adaptiq.titan.flow.parser;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.adaptiq.titan.flow.model.StepModel;
import io.adaptiq.titan.flow.parser.grammar.GrammarKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code use:} grammar scope (design/56) — stage-level local-template inlining. A stage
 * carrying {@code use: { from, with }} reads the template file at parse time, validates the
 * supplied {@code with:} args against the template's declared {@code params:}, substitutes {@code
 * ${param.<name>}} occurrences, and inlines the resulting step list onto the stage.
 *
 * <p>{@code use:} is <strong>stage-only</strong> ({@link #appliesToStep()} returns {@code false}) —
 * it is a step-source replacement, not a single-step attribute. The scope is mutually exclusive
 * with the stage's own {@code steps:} key and with the {@code matrix:}/{@code each:} scopes — see
 * {@link #parseStageAndFlatten} for the contract.
 *
 * <p>v1 (design/56 §5): local relative paths only — remote / git-ref'd reuse is the domain of
 * design/53 (`libraries:`). Templates are flat: a template's {@code steps:} may not itself contain
 * a {@code use:} (nested templates are v2).
 *
 * <p>The resolver is injected via a parser-local {@link ThreadLocal} that {@link TitanYamlParser}
 * sets at the top of every {@code parse(yaml, baseDir)} call and clears in a {@code finally}. This
 * keeps the {@link StepScope} signatures stable (no resolver-dependency leaks into the interface)
 * while letting tests stub the filesystem.
 */
final class TemplateScope implements StepScope {

  static final String KEY = "use";

  /** Allowed keys inside the {@code use:} object. */
  private static final Set<String> USE_KEYS = Set.of("from", "with");

  /** Allowed keys inside a template file. */
  private static final Set<String> TEMPLATE_KEYS = Set.of("params", "steps");

  /** Allowed keys inside one {@code params[<name>]} entry. */
  private static final Set<String> PARAM_DECL_KEYS = Set.of("required", "type", "default");

  /** Recognised {@code params[].type} values. */
  private static final Set<String> PARAM_TYPES = Set.of("string", "integer", "boolean");

  /** Identifier shape for a parameter name. */
  private static final Pattern PARAM_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** Matches {@code ${param.<name>}} occurrences in a string. */
  private static final Pattern PARAM_REF = Pattern.compile("\\$\\{param\\.([A-Za-z0-9_]+)\\}");

  private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

  /** Per-parse resolver, set by {@link TitanYamlParser#parse(String, java.nio.file.Path)}. */
  private static final ThreadLocal<TemplateResolver> RESOLVER = new ThreadLocal<>();

  /** Install the resolver for the current parse. Called once at the top of {@code parse()}. */
  static void setResolver(@NonNull TemplateResolver resolver) {
    RESOLVER.set(resolver);
  }

  /** Clear the resolver after a parse — paired with {@link #setResolver} in a try/finally. */
  static void clearResolver() {
    RESOLVER.remove();
  }

  @Override
  public String key() {
    return KEY;
  }

  @Override
  public GrammarKey schema() {
    return GrammarKey.optional(
        KEY,
        "Stage-level local-template inlining (design/56). The template's step list replaces "
            + "the stage's steps at parse time, with ${param.<name>} substituted from 'with'. "
            + "Mutually exclusive with 'steps:', 'matrix:' and 'each:' on the same stage.",
        GrammarKey.ref("use"));
  }

  @Override
  public boolean appliesToStep() {
    return false; // use: is stage-structural; no step-level form.
  }

  @Override
  public void parseStep(JsonNode stepNode, StepModel step, ParseContext ctx) {
    // use: is stage-only — nothing to do on a step.
  }

  @Override
  public void parseStageAndFlatten(JsonNode stageNode, List<StepModel> steps, ParseContext ctx) {
    JsonNode node = stageNode.get(KEY);
    if (node == null) {
      return;
    }
    // design/56 §3: 'use:' is mutually exclusive with the stage's own 'steps:'. We detect that
    // here, before any I/O — `steps` (the StepScope param) is the parser-built list from the
    // stage's `steps:` key; non-empty means the user wrote both.
    if (!steps.isEmpty()) {
      throw new PipelineParseException(
          ctx.location()
              + ": 'use' and 'steps' are mutually exclusive on the same stage — "
              + "the template's step list IS the stage's steps");
    }
    // design/56 §3: 'use:' is mutually exclusive with 'matrix:' and 'each:' on the same stage
    // (template-of-matrix and matrix-of-template are v2).
    if (stageNode.has(MatrixScope.KEY)) {
      throw new PipelineParseException(
          ctx.location() + ": 'use' and 'matrix' are mutually exclusive on the same stage (v1)");
    }
    if (stageNode.has(EachScope.KEY)) {
      throw new PipelineParseException(
          ctx.location() + ": 'use' and 'each' are mutually exclusive on the same stage (v1)");
    }

    UseSpec spec = parseUseBlock(node, ctx.location() + " use");
    TemplateResolver resolver = RESOLVER.get();
    if (resolver == null) {
      // Defensive: TitanYamlParser must have installed one. A null resolver here is a
      // programming error in the parser wiring, not a user-input failure.
      throw new PipelineParseException(
          ctx.location()
              + ": 'use:' encountered but no TemplateResolver is installed — "
              + "the pipeline parser must be called with a base directory");
    }
    TemplateResolver.TemplateContent content =
        resolver.resolve(spec.from(), ctx.location() + " use");
    JsonNode templateRoot = parseTemplate(content, ctx.location() + " use");
    ParseSupport.rejectUnknownKeys(
        templateRoot, TEMPLATE_KEYS, "template '" + content.resolvedPath() + "'");

    Map<String, ParamDecl> params =
        parseParamsBlock(
            templateRoot.get("params"), "template '" + content.resolvedPath() + "' params");
    Map<String, Object> resolvedArgs =
        resolveArgs(params, spec.with(), ctx.location() + " use with");

    JsonNode stepsNode = templateRoot.get("steps");
    if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
      throw new PipelineParseException(
          "template '"
              + content.resolvedPath()
              + "': 'steps' is required and must be a non-empty list");
    }
    // design/56 §5: recursive 'use:' (a template whose own steps contain a 'use:' key) is a
    // v1 nesting violation. Detect at parse-time — fail loud rather than silently skip.
    rejectNestedUse(stepsNode, "template '" + content.resolvedPath() + "'");

    // Build the inlined step list. We delegate to TitanYamlParser's step-parsing path by
    // synthesizing a stage-id-prefixed step list — but since parseStep() is private and we are
    // inside the scope loop, the simplest correct path is to inline-build StepModel here using
    // the same logic-shape as the parser, then apply ${param.X} substitution. Doing this in the
    // scope keeps the parser's public surface unchanged.
    List<StepModel> inlined = new ArrayList<>(stepsNode.size());
    int idx = 0;
    for (JsonNode stepNode : stepsNode) {
      StepModel step = buildTemplateStep(stepNode, ctx.stage().getId(), idx++, content, params);
      // Substitute ${param.X} in every string-valued argument / env / runtime / body.
      substituteStep(step, resolvedArgs, params, content);
      inlined.add(step);
    }
    // Replace the stage's step list in-place — the StepScope contract gives us a mutable
    // reference. Subsequent scopes (credentials/sshAgent/image flatten…) see the inlined list.
    steps.addAll(inlined);
  }

  // ── 'use:' block parsing ─────────────────────────────────────────────────

  @NonNull
  private static UseSpec parseUseBlock(@NonNull JsonNode node, @NonNull String context) {
    if (node.isNull()) {
      throw new PipelineParseException(context + ": 'use' must not be null");
    }
    if (!node.isObject()) {
      throw new PipelineParseException(
          context
              + ": 'use' must be an object { from, with? }, got "
              + TypedNodeReader.describe(node));
    }
    ParseSupport.rejectUnknownKeys(node, USE_KEYS, context);

    JsonNode fromNode = node.get("from");
    if (fromNode == null) {
      throw new PipelineParseException(context + ": 'from' is required");
    }
    if (fromNode.isNull() || !fromNode.isTextual() || fromNode.asText().isBlank()) {
      throw new PipelineParseException(
          context
              + ": 'from' must be a non-blank string, got "
              + TypedNodeReader.describe(fromNode));
    }
    String from = fromNode.asText();

    Map<String, Object> with = new LinkedHashMap<>();
    JsonNode withNode = node.get("with");
    if (withNode != null && !withNode.isNull()) {
      if (!withNode.isObject()) {
        throw new PipelineParseException(
            context + ": 'with' must be an object, got " + TypedNodeReader.describe(withNode));
      }
      Iterator<Map.Entry<String, JsonNode>> it = withNode.fields();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        JsonNode v = e.getValue();
        if (v == null || v.isNull()) {
          throw new PipelineParseException(
              context + ".with['" + e.getKey() + "']: value must not be null");
        }
        if (!v.isValueNode()) {
          throw new PipelineParseException(
              context
                  + ".with['"
                  + e.getKey()
                  + "']: value must be a scalar (string, number, or boolean), got "
                  + TypedNodeReader.describe(v));
        }
        with.put(e.getKey(), scalarValue(v));
      }
    }
    return new UseSpec(from, with);
  }

  // ── template file parsing ────────────────────────────────────────────────

  @NonNull
  private static JsonNode parseTemplate(
      @NonNull TemplateResolver.TemplateContent content, @NonNull String context) {
    if (content.text().isBlank()) {
      throw new PipelineParseException(
          context + ": template file '" + content.resolvedPath() + "' is empty");
    }
    JsonNode root;
    try {
      root = YAML.readTree(content.text());
    } catch (JacksonException e) {
      throw new PipelineParseException(
          context
              + ": template '"
              + content.resolvedPath()
              + "' is not valid YAML: "
              + e.getOriginalMessage(),
          e);
    }
    if (root == null || !root.isObject()) {
      throw new PipelineParseException(
          context + ": template '" + content.resolvedPath() + "' must be a YAML object");
    }
    return root;
  }

  @NonNull
  private static Map<String, ParamDecl> parseParamsBlock(
      @Nullable JsonNode paramsNode, @NonNull String context) {
    Map<String, ParamDecl> out = new LinkedHashMap<>();
    if (paramsNode == null || paramsNode.isNull()) {
      return out;
    }
    if (!paramsNode.isObject()) {
      throw new PipelineParseException(
          context + ": 'params' must be a map of <name>: { required?, type?, default? }");
    }
    Iterator<Map.Entry<String, JsonNode>> it = paramsNode.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      String name = e.getKey();
      if (!PARAM_NAME.matcher(name).matches()) {
        throw new PipelineParseException(
            context
                + ": param name '"
                + name
                + "' must match [A-Za-z_][A-Za-z0-9_]* — it is the substitution binding");
      }
      JsonNode decl = e.getValue();
      if (decl == null || decl.isNull()) {
        // Bare `name:` — an optional string param with no default.
        out.put(name, new ParamDecl(false, "string", null));
        continue;
      }
      if (!decl.isObject()) {
        throw new PipelineParseException(
            context + ".params['" + name + "'] must be an object { required?, type?, default? }");
      }
      ParseSupport.rejectUnknownKeys(decl, PARAM_DECL_KEYS, context + ".params['" + name + "']");
      boolean required = false;
      JsonNode requiredNode = decl.get("required");
      if (requiredNode != null && !requiredNode.isNull()) {
        if (!requiredNode.isBoolean()) {
          throw new PipelineParseException(
              context + ".params['" + name + "'].required must be a boolean");
        }
        required = requiredNode.booleanValue();
      }
      String type = "string";
      JsonNode typeNode = decl.get("type");
      if (typeNode != null && !typeNode.isNull()) {
        if (!typeNode.isTextual()) {
          throw new PipelineParseException(
              context + ".params['" + name + "'].type must be a string");
        }
        type = typeNode.asText();
        if (!PARAM_TYPES.contains(type)) {
          throw new PipelineParseException(
              context
                  + ".params['"
                  + name
                  + "'].type must be one of "
                  + PARAM_TYPES
                  + " (got '"
                  + type
                  + "')");
        }
      }
      Object defaultValue = null;
      JsonNode defaultNode = decl.get("default");
      if (defaultNode != null && !defaultNode.isNull()) {
        if (!defaultNode.isValueNode()) {
          throw new PipelineParseException(
              context + ".params['" + name + "'].default must be a scalar");
        }
        defaultValue =
            coerceToType(
                scalarValue(defaultNode), type, context + ".params['" + name + "'].default");
      }
      out.put(name, new ParamDecl(required, type, defaultValue));
    }
    return out;
  }

  // ── arg resolution + type coercion ───────────────────────────────────────

  @NonNull
  private static Map<String, Object> resolveArgs(
      @NonNull Map<String, ParamDecl> params,
      @NonNull Map<String, Object> with,
      @NonNull String context) {
    // 1. Reject unknown keys in `with:` — every key must name a declared param.
    for (String key : with.keySet()) {
      if (!params.containsKey(key)) {
        throw new PipelineParseException(
            context
                + ": '"
                + key
                + "' is not a declared template param (declared: "
                + params.keySet()
                + ")");
      }
    }
    // 2. For every declared param, resolve from `with:` then default, then required-check.
    Map<String, Object> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, ParamDecl> e : params.entrySet()) {
      String name = e.getKey();
      ParamDecl decl = e.getValue();
      if (with.containsKey(name)) {
        Object coerced = coerceToType(with.get(name), decl.type(), context + "['" + name + "']");
        resolved.put(name, coerced);
      } else if (decl.defaultValue() != null) {
        resolved.put(name, decl.defaultValue());
      } else if (decl.required()) {
        throw new PipelineParseException(
            context
                + ": required template param '"
                + name
                + "' is missing from 'with:' and has no default");
      }
      // else: optional with no default and not supplied → no entry; substitution will reject
      // any ${param.<name>} reference for it.
    }
    return resolved;
  }

  @NonNull
  private static Object coerceToType(
      @NonNull Object raw, @NonNull String type, @NonNull String context) {
    switch (type) {
      case "string":
        return raw.toString();
      case "integer":
        if (raw instanceof Long || raw instanceof Integer) {
          return raw;
        }
        if (raw instanceof Number n) {
          double d = n.doubleValue();
          if (d != Math.floor(d) || Double.isInfinite(d)) {
            throw new PipelineParseException(
                context + ": expected integer, got non-integral number " + raw);
          }
          return n.longValue();
        }
        if (raw instanceof String s) {
          try {
            return Long.parseLong(s);
          } catch (NumberFormatException nfe) {
            throw new PipelineParseException(
                context + ": expected integer, got string '" + s + "'");
          }
        }
        throw new PipelineParseException(context + ": expected integer, got " + raw.getClass());
      case "boolean":
        if (raw instanceof Boolean) {
          return raw;
        }
        throw new PipelineParseException(context + ": expected boolean, got " + raw);
      default:
        throw new IllegalStateException("unknown param type '" + type + "'");
    }
  }

  // ── template-step building (a focused mini-parser, design/56 §6) ─────────

  @NonNull
  private static StepModel buildTemplateStep(
      @NonNull JsonNode stepNode,
      @NonNull String stageId,
      int idx,
      @NonNull TemplateResolver.TemplateContent content,
      @NonNull Map<String, ParamDecl> params) {
    if (!stepNode.isObject() || stepNode.isEmpty()) {
      throw new PipelineParseException(
          "template '"
              + content.resolvedPath()
              + "' step "
              + idx
              + ": each step must be a map with one descriptor key");
    }
    // Use the parser's STEP_KEYS (computed scope union) to recognise non-descriptor sibling keys
    // exactly the way TitanYamlParser does — so a template step `{ sh: foo, image: bar }` works.
    String descriptor = null;
    JsonNode value = null;
    Iterator<Map.Entry<String, JsonNode>> fields = stepNode.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> e = fields.next();
      if (TitanYamlParser.stepScopeKeys().contains(e.getKey())) {
        continue;
      }
      if (descriptor != null) {
        throw new PipelineParseException(
            "template '"
                + content.resolvedPath()
                + "' step "
                + idx
                + ": a step has exactly one descriptor key (found '"
                + descriptor
                + "' and '"
                + e.getKey()
                + "')");
      }
      descriptor = e.getKey();
      value = e.getValue();
    }
    if (descriptor == null) {
      throw new PipelineParseException(
          "template '" + content.resolvedPath() + "' step " + idx + ": no descriptor key");
    }
    StepModel step = new StepModel();
    step.setId(stageId + "-s" + idx);
    step.setDescriptorId(descriptor);
    if (value == null || value.isNull()) {
      step.setArguments(new LinkedHashMap<>());
    } else if (value.isObject()) {
      step.setArguments(toArgMap(value));
    } else if (value.isValueNode()) {
      // Bare scalar — fold into `value` (the parser's generic rule, design/42 §4.6).
      Map<String, Object> args = new LinkedHashMap<>();
      args.put("value", value.asText());
      step.setArguments(args);
    } else {
      throw new PipelineParseException(
          "template '"
              + content.resolvedPath()
              + "' step "
              + idx
              + ": descriptor '"
              + descriptor
              + "' value must be a scalar, map or null");
    }
    return step;
  }

  @NonNull
  private static Map<String, Object> toArgMap(@NonNull JsonNode obj) {
    Map<String, Object> args = new LinkedHashMap<>();
    Iterator<Map.Entry<String, JsonNode>> it = obj.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> e = it.next();
      args.put(e.getKey(), YAML.convertValue(e.getValue(), Object.class));
    }
    return args;
  }

  private static void rejectNestedUse(@NonNull JsonNode stepsNode, @NonNull String context) {
    int idx = 0;
    for (JsonNode step : stepsNode) {
      if (step.isObject() && step.has(KEY)) {
        throw new PipelineParseException(
            context
                + " step "
                + idx
                + ": nested 'use:' is not supported in v1 (recursive templates are v2; "
                + "design/56 §5)");
      }
      idx++;
    }
  }

  // ── substitution ─────────────────────────────────────────────────────────

  private static void substituteStep(
      @NonNull StepModel step,
      @NonNull Map<String, Object> resolvedArgs,
      @NonNull Map<String, ParamDecl> params,
      @NonNull TemplateResolver.TemplateContent content) {
    step.setRuntime(substitute(step.getRuntime(), resolvedArgs, params, content));
    step.setBody(substitute(step.getBody(), resolvedArgs, params, content));
    Map<String, Object> newArgs =
        substituteArgs(step.getArguments(), resolvedArgs, params, content);
    step.setArguments(newArgs);
    if (step.getEnv() != null) {
      Map<String, String> newEnv = new LinkedHashMap<>();
      for (Map.Entry<String, String> e : step.getEnv().entrySet()) {
        newEnv.put(e.getKey(), substitute(e.getValue(), resolvedArgs, params, content));
      }
      step.setEnv(newEnv);
    }
  }

  @NonNull
  private static Map<String, Object> substituteArgs(
      @NonNull Map<String, Object> src,
      @NonNull Map<String, Object> resolved,
      @NonNull Map<String, ParamDecl> params,
      @NonNull TemplateResolver.TemplateContent content) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : src.entrySet()) {
      out.put(e.getKey(), substituteValue(e.getValue(), resolved, params, content));
    }
    return out;
  }

  @Nullable
  private static Object substituteValue(
      @Nullable Object v,
      @NonNull Map<String, Object> resolved,
      @NonNull Map<String, ParamDecl> params,
      @NonNull TemplateResolver.TemplateContent content) {
    if (v instanceof String s) {
      return substitute(s, resolved, params, content);
    }
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      for (Map.Entry<?, ?> e : m.entrySet()) {
        out.put(
            String.valueOf(e.getKey()), substituteValue(e.getValue(), resolved, params, content));
      }
      return out;
    }
    if (v instanceof List<?> list) {
      List<Object> out = new ArrayList<>(list.size());
      for (Object item : list) {
        out.add(substituteValue(item, resolved, params, content));
      }
      return out;
    }
    return v;
  }

  @Nullable
  private static String substitute(
      @Nullable String input,
      @NonNull Map<String, Object> resolved,
      @NonNull Map<String, ParamDecl> params,
      @NonNull TemplateResolver.TemplateContent content) {
    if (input == null) {
      return null;
    }
    Matcher m = PARAM_REF.matcher(input);
    if (!m.find()) {
      return input;
    }
    StringBuilder sb = new StringBuilder();
    m.reset();
    while (m.find()) {
      String name = m.group(1);
      if (!params.containsKey(name)) {
        throw new PipelineParseException(
            "template '"
                + content.resolvedPath()
                + "': ${param."
                + name
                + "} references an undeclared param (declared: "
                + params.keySet()
                + ")");
      }
      if (!resolved.containsKey(name)) {
        // declared optional with no default and not supplied → ${param.X} has no value.
        throw new PipelineParseException(
            "template '"
                + content.resolvedPath()
                + "': ${param."
                + name
                + "} has no value — param is optional with no default and was not "
                + "supplied via 'with:'");
      }
      m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(resolved.get(name))));
    }
    m.appendTail(sb);
    return sb.toString();
  }

  @NonNull
  private static Object scalarValue(@NonNull JsonNode v) {
    if (v.isBoolean()) {
      return v.booleanValue();
    }
    if (v.isIntegralNumber()) {
      return v.asLong();
    }
    if (v.isFloatingPointNumber()) {
      return v.asDouble();
    }
    return v.asText();
  }

  // ── carriers ─────────────────────────────────────────────────────────────

  /** The parsed {@code use:} block from a stage YAML node. */
  private record UseSpec(@NonNull String from, @NonNull Map<String, Object> with) {}

  /** A template's declared parameter — its required-ness, type and (coerced) default value. */
  private record ParamDecl(boolean required, @NonNull String type, @Nullable Object defaultValue) {}
}
