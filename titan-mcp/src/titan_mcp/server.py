"""
MCP server runtime for titan-mcp.

Builds a :class:`mcp.server.Server` whose tool catalogue is generated from an
OpenAPI document. Each ``tools/call`` invocation is translated into one HTTP
request against the Titan controller, authenticated with the ``TITAN_TOKEN``
PAT (see PR #464). Failures from the controller surface as **structured**
errors — never raw exceptions — so MCP clients can render them without crashing
the session.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass
from typing import Any, Mapping

import httpx

from titan_mcp.generator import GeneratedTool, GenerationReport, generate_tools, load_schema

log = logging.getLogger("titan_mcp.server")

_DEFAULT_TIMEOUT_S = 30.0
_BODY_TRUNCATE = 4096


@dataclass
class TitanClientConfig:
    base_url: str | None
    token: str | None
    timeout_s: float = _DEFAULT_TIMEOUT_S

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "TitanClientConfig":
        env = env if env is not None else os.environ
        return cls(
            base_url=(env.get("TITAN_BASE_URL") or "").rstrip("/") or None,
            token=env.get("TITAN_TOKEN") or None,
        )


def auth_error() -> dict[str, Any]:
    return {
        "ok": False,
        "error": "auth_required",
        "hint": "set TITAN_TOKEN env (a Titan PAT, see PR #464)",
    }


def base_url_error() -> dict[str, Any]:
    return {
        "ok": False,
        "error": "base_url_required",
        "hint": "set TITAN_BASE_URL env to the Titan controller, e.g. https://titan.example.com",
    }


async def invoke_tool(
    tool: GeneratedTool,
    arguments: Mapping[str, Any],
    config: TitanClientConfig,
    *,
    client_factory=None,
) -> dict[str, Any]:
    """Translate ``arguments`` into an HTTP request and return a structured dict.

    Never raises for HTTP-level failures — the MCP contract is that a tool
    returns a result. Network failures (DNS / connect / read) become
    ``{ok: false, error: "transport", ...}`` so the assistant can retry or
    explain.
    """
    if not config.token:
        return auth_error()
    if not config.base_url:
        return base_url_error()

    # Path substitution. Every path-template variable is required by the
    # generator, so a missing one is a caller bug — surface it structured.
    path = tool.path
    for name in tool.path_params:
        if name not in arguments:
            return {
                "ok": False,
                "error": "missing_argument",
                "argument": name,
                "hint": f"path parameter '{name}' is required for {tool.name}",
            }
        path = path.replace("{" + name + "}", _quote(str(arguments[name])))

    params = {
        name: arguments[name]
        for name in tool.query_params
        if name in arguments and arguments[name] is not None
    }

    body: Any = None
    if tool.body_param and tool.body_param in arguments:
        body = arguments[tool.body_param]

    headers = {
        "Authorization": f"Bearer {config.token}",
        "Accept": "application/json",
        "User-Agent": "titan-mcp/0.1",
    }

    url = f"{config.base_url}{path}"
    factory = client_factory or (lambda: httpx.AsyncClient(timeout=config.timeout_s))
    try:
        async with factory() as client:
            response = await client.request(
                tool.method,
                url,
                params=params or None,
                json=body if body is not None else None,
                headers=headers,
            )
    except httpx.HTTPError as exc:
        return {
            "ok": False,
            "error": "transport",
            "message": str(exc),
            "url": url,
        }

    return _coerce_response(response)


def _quote(value: str) -> str:
    # Path-param values are interpolated into the URL — use the std URL-safe
    # quoting rules so callers can pass things like "feature/x" without us
    # silently breaking the path. Reserve "/" because JAX-RS will not match
    # an encoded slash for a single path segment.
    from urllib.parse import quote

    return quote(value, safe="")


def _coerce_response(response: httpx.Response) -> dict[str, Any]:
    text = response.text or ""
    truncated = text[:_BODY_TRUNCATE]
    parsed: Any
    if "application/json" in (response.headers.get("content-type") or "").lower():
        try:
            parsed = json.loads(text) if text else None
        except json.JSONDecodeError:
            parsed = None
    else:
        parsed = None

    if response.is_success:
        return {
            "ok": True,
            "status": response.status_code,
            "json": parsed,
            "body": None if parsed is not None else truncated,
        }

    # Non-2xx — structured error. NEVER raise.
    return {
        "ok": False,
        "error": "http_error",
        "status": response.status_code,
        "body": truncated,
        "json": parsed,
    }


# ─────────────────────────────────────────────────────────────────────────────
# MCP server wiring
# ─────────────────────────────────────────────────────────────────────────────


def build_report(schema_path: str) -> GenerationReport:
    """Convenience: load + generate. Raises :class:`SchemaError` on dup-operationId."""
    return generate_tools(load_schema(schema_path))


async def run_stdio_server(schema_path: str) -> None:  # pragma: no cover - I/O loop
    """Boot the MCP stdio server. Imported lazily so unit tests don't pull in mcp."""
    from mcp.server import Server
    from mcp.server.stdio import stdio_server
    from mcp.types import TextContent, Tool

    report = build_report(schema_path)
    config = TitanClientConfig.from_env()
    tools_by_name = {t.name: t for t in report.tools}
    log.info("titan-mcp: %s", report.summary())

    server: Server = Server("titan-mcp")

    @server.list_tools()
    async def _list_tools() -> list[Tool]:
        return [
            Tool(name=t.name, description=t.description, inputSchema=t.input_schema)
            for t in report.tools
        ]

    @server.call_tool()
    async def _call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
        tool = tools_by_name.get(name)
        if tool is None:
            payload = {"ok": False, "error": "unknown_tool", "name": name}
        else:
            payload = await invoke_tool(tool, arguments or {}, config)
        return [TextContent(type="text", text=json.dumps(payload, ensure_ascii=False))]

    async with stdio_server() as (read, write):
        await server.run(read, write, server.create_initialization_options())
