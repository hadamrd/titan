"""
OpenAPI → MCP tool generator for titan-mcp.

Walks an OpenAPI 3.x document and produces one :class:`GeneratedTool` per
documented operation. The generator is intentionally minimal: it covers the
operation shapes the issue calls out as in-scope (GET with query / path params,
POST / PATCH / PUT with a JSON body, DELETE with path params) and exposes the
gaps it can't handle so the diagnose subcommand can report them.

Design notes
============
* **Tool name = operationId**. The MCP spec encourages stable tool names; the
  OpenAPI operationId is the canonical stable handle on a REST operation.
  Duplicate operationIds raise at startup — silently overwriting one tool with
  another would let the wrong endpoint run under a familiar name.
* **No JSON-Schema-Draft conversion pass.** We forward the OpenAPI parameter
  schemas directly into the MCP ``inputSchema``. The shapes overlap for the
  primitive cases we exercise; full Draft 2020-12 conformance is left to a
  follow-up.
* **No code generation step.** Tools are descriptors built once at import and
  executed against ``httpx`` at call time. This is what keeps the
  "regenerate from schema" loop cheap.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping

# Operation shapes the generator knows how to surface as tools. Anything else is
# routed to ``GeneratedTool.skipped`` with a reason — diagnose reports them and
# the integration test asserts N == M against the documented operation count.
_SUPPORTED_METHODS = {"get", "post", "put", "patch", "delete"}


class SchemaError(RuntimeError):
    """Raised for unrecoverable problems in the OpenAPI document."""


@dataclass(frozen=True)
class GeneratedTool:
    """One MCP tool descriptor synthesised from one OpenAPI operation."""

    name: str
    """The MCP tool name — equal to the OpenAPI ``operationId``."""

    description: str
    """Concatenation of ``summary`` and ``description`` (either may be empty)."""

    method: str
    """HTTP verb in upper case (``GET``, ``POST``, ...)."""

    path: str
    """OpenAPI path template, e.g. ``/api/v1/jobs/{jobId}``."""

    input_schema: dict[str, Any]
    """JSON Schema for the ``arguments`` payload of an MCP ``tools/call``."""

    path_params: tuple[str, ...] = ()
    query_params: tuple[str, ...] = ()
    body_param: str | None = None
    """Name of the argument that carries the JSON request body, if any."""


@dataclass(frozen=True)
class SkippedOperation:
    """Records an operation the generator declined to surface as a tool."""

    method: str
    path: str
    reason: str


@dataclass
class GenerationReport:
    """Summary returned alongside the generated tool list."""

    tools: list[GeneratedTool] = field(default_factory=list)
    skipped: list[SkippedOperation] = field(default_factory=list)
    documented: int = 0
    """Total operations seen in the schema, before skips."""

    def summary(self) -> str:
        reasons = ", ".join(f"{s.method} {s.path}: {s.reason}" for s in self.skipped) or "none"
        return (
            f"generated {len(self.tools)} tools from {self.documented} operations "
            f"(skipped {len(self.skipped)}: {reasons})"
        )


# ─────────────────────────────────────────────────────────────────────────────
# Public API
# ─────────────────────────────────────────────────────────────────────────────


def load_schema(path: str | Path) -> dict[str, Any]:
    """Read an OpenAPI document from disk. Raises :class:`SchemaError` on parse failure."""
    p = Path(path)
    try:
        with p.open("r", encoding="utf-8") as fh:
            data = json.load(fh)
    except FileNotFoundError as exc:
        raise SchemaError(f"openapi schema not found: {p}") from exc
    except json.JSONDecodeError as exc:
        raise SchemaError(f"openapi schema is not valid JSON ({p}): {exc}") from exc
    if not isinstance(data, dict) or "paths" not in data:
        raise SchemaError(f"openapi schema at {p} is missing required 'paths' field")
    return data


def generate_tools(schema: Mapping[str, Any]) -> GenerationReport:
    """Walk the OpenAPI document and return a :class:`GenerationReport`."""
    report = GenerationReport()
    seen_names: dict[str, str] = {}

    paths = schema.get("paths") or {}
    if not isinstance(paths, Mapping):
        raise SchemaError("openapi 'paths' must be an object")

    components = schema.get("components") or {}

    for raw_path, path_item in paths.items():
        if not isinstance(path_item, Mapping):
            continue
        path_level_params = list(_iter_params(path_item.get("parameters"), components))
        for method, op in path_item.items():
            if method.lower() not in _SUPPORTED_METHODS:
                continue
            if not isinstance(op, Mapping):
                continue
            report.documented += 1
            try:
                tool = _build_tool(raw_path, method.lower(), op, path_level_params, components)
            except _Skip as skip:
                report.skipped.append(
                    SkippedOperation(method=method.upper(), path=raw_path, reason=skip.reason)
                )
                continue

            # Duplicate operationIds are a defect in the producer — refuse to
            # mask one operation behind another. The CTO's lesson: silent
            # overwrite-on-conflict is a foot-gun, especially when a generator
            # is consuming an upstream artifact.
            if tool.name in seen_names:
                raise SchemaError(
                    f"duplicate operationId '{tool.name}': "
                    f"used by both '{seen_names[tool.name]}' and '{method.upper()} {raw_path}'"
                )
            seen_names[tool.name] = f"{method.upper()} {raw_path}"
            report.tools.append(tool)

    return report


# ─────────────────────────────────────────────────────────────────────────────
# Internals
# ─────────────────────────────────────────────────────────────────────────────


class _Skip(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(reason)
        self.reason = reason


def _build_tool(
    raw_path: str,
    method: str,
    op: Mapping[str, Any],
    path_level_params: list[dict[str, Any]],
    components: Mapping[str, Any],
) -> GeneratedTool:
    operation_id = op.get("operationId")
    if not operation_id or not isinstance(operation_id, str):
        raise _Skip("no operationId")

    summary = (op.get("summary") or "").strip()
    description = (op.get("description") or "").strip()
    desc = "\n\n".join(s for s in (summary, description) if s) or f"{method.upper()} {raw_path}"

    properties: dict[str, Any] = {}
    required: list[str] = []
    path_params: list[str] = []
    query_params: list[str] = []
    body_param: str | None = None

    op_params = list(_iter_params(op.get("parameters"), components))
    merged_params = _merge_params(path_level_params, op_params)
    for param in merged_params:
        loc = param.get("in")
        name = param.get("name")
        if not name or loc not in {"path", "query"}:
            # header / cookie params are intentionally unsupported — Titan's
            # API uses neither for documented endpoints; treat as a soft skip
            # rather than failing the whole tool.
            continue
        schema = _resolve_ref(param.get("schema") or {"type": "string"}, components)
        properties[name] = _annotate(schema, param.get("description"))
        if loc == "path":
            path_params.append(name)
            required.append(name)
        else:
            query_params.append(name)
            if param.get("required"):
                required.append(name)

    request_body = op.get("requestBody")
    if isinstance(request_body, Mapping):
        body_schema = _extract_json_body(request_body, components)
        if body_schema is None:
            raise _Skip("requestBody has no application/json content")
        body_param = "body"
        properties[body_param] = _annotate(body_schema, "Request body (application/json).")
        if request_body.get("required"):
            required.append(body_param)

    # Sanity: every path-template variable must be surfaced. Missing ones mean
    # the schema is internally inconsistent — bubble that up rather than emit a
    # tool that can't construct a valid URL.
    template_vars = _path_template_vars(raw_path)
    missing = [v for v in template_vars if v not in path_params]
    if missing:
        raise _Skip(f"path template references undefined parameter(s): {missing}")

    input_schema = {
        "type": "object",
        "properties": properties,
        "required": sorted(set(required)),
        "additionalProperties": False,
    }
    return GeneratedTool(
        name=operation_id,
        description=desc,
        method=method.upper(),
        path=raw_path,
        input_schema=input_schema,
        path_params=tuple(path_params),
        query_params=tuple(query_params),
        body_param=body_param,
    )


def _iter_params(
    raw: Any, components: Mapping[str, Any]
) -> Iterable[dict[str, Any]]:
    if not isinstance(raw, list):
        return []
    out: list[dict[str, Any]] = []
    for entry in raw:
        if isinstance(entry, Mapping) and "$ref" in entry:
            resolved = _resolve_ref(dict(entry), components)
            if isinstance(resolved, Mapping):
                out.append(dict(resolved))
        elif isinstance(entry, Mapping):
            out.append(dict(entry))
    return out


def _merge_params(
    path_level: list[dict[str, Any]], op_level: list[dict[str, Any]]
) -> list[dict[str, Any]]:
    """Operation-level params override path-level params with the same (name, in)."""
    by_key: dict[tuple[str, str], dict[str, Any]] = {}
    for p in path_level + op_level:
        key = (p.get("name", ""), p.get("in", ""))
        by_key[key] = p
    return list(by_key.values())


def _extract_json_body(
    request_body: Mapping[str, Any], components: Mapping[str, Any]
) -> dict[str, Any] | None:
    content = request_body.get("content")
    if not isinstance(content, Mapping):
        return None
    json_entry = content.get("application/json")
    if not isinstance(json_entry, Mapping):
        return None
    schema = json_entry.get("schema")
    if not isinstance(schema, Mapping):
        return {"type": "object"}
    return _resolve_ref(dict(schema), components)


def _resolve_ref(schema: dict[str, Any], components: Mapping[str, Any]) -> dict[str, Any]:
    """Dereference a single ``$ref`` to ``#/components/schemas/<name>``.

    Nested refs are NOT recursively chased — the MCP client only needs enough
    structure to validate arguments, and most consumers tolerate ``$ref`` in
    nested positions. Documented in :mod:`titan_mcp.generator` as a known gap.
    """
    ref = schema.get("$ref")
    if not isinstance(ref, str):
        return schema
    prefix = "#/components/schemas/"
    if not ref.startswith(prefix):
        return schema
    name = ref[len(prefix) :]
    schemas = (components or {}).get("schemas") or {}
    target = schemas.get(name)
    if not isinstance(target, Mapping):
        return schema
    return dict(target)


def _annotate(schema: Mapping[str, Any], description: str | None) -> dict[str, Any]:
    out = dict(schema)
    if description and not out.get("description"):
        out["description"] = description
    return out


def _path_template_vars(template: str) -> list[str]:
    out: list[str] = []
    depth = 0
    start = -1
    for i, ch in enumerate(template):
        if ch == "{":
            depth += 1
            if depth == 1:
                start = i + 1
        elif ch == "}":
            if depth == 1 and start >= 0:
                out.append(template[start:i])
                start = -1
            depth -= 1
    return out
