"""
Entry point for the ``titan-mcp`` console script.

Two subcommands:
    titan-mcp serve      Run the MCP stdio server (default).
    titan-mcp diagnose   Print the generation report — handy for "did my
                         schema produce the tools I expected" debugging.

The schema is resolved (in order): ``--schema`` flag, ``TITAN_OPENAPI_SCHEMA``
env var, then the bundled snapshot at ``titan_mcp/schema/openapi.json``.
"""

from __future__ import annotations

import argparse
import asyncio
import logging
import os
import sys
from importlib import resources
from pathlib import Path

from titan_mcp.generator import SchemaError
from titan_mcp.server import build_report, run_stdio_server


def _bundled_schema_path() -> str:
    # Resolve via importlib.resources so this also works inside a wheel.
    try:
        ref = resources.files("titan_mcp.schema").joinpath("openapi.json")
    except (ModuleNotFoundError, FileNotFoundError):
        return str(Path(__file__).resolve().parent / "schema" / "openapi.json")
    return str(ref)


def _resolve_schema(arg: str | None) -> str:
    if arg:
        return arg
    env = os.environ.get("TITAN_OPENAPI_SCHEMA")
    if env:
        return env
    return _bundled_schema_path()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="titan-mcp", description=__doc__)
    parser.add_argument(
        "--schema",
        help="Path to an OpenAPI 3.x document. Defaults to the bundled snapshot.",
    )
    parser.add_argument(
        "-v", "--verbose", action="store_true", help="DEBUG-level logging on stderr."
    )
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("serve", help="Run the MCP stdio server (default).")
    sub.add_parser("diagnose", help="Print generation report and exit.")

    args = parser.parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )

    schema_path = _resolve_schema(args.schema)

    if args.cmd == "diagnose":
        try:
            report = build_report(schema_path)
        except SchemaError as exc:
            print(f"titan-mcp: schema error: {exc}", file=sys.stderr)
            return 2
        print(report.summary())
        for tool in report.tools:
            print(f"  - {tool.method} {tool.path} -> {tool.name}")
        return 0

    # default = serve
    try:
        asyncio.run(run_stdio_server(schema_path))
    except KeyboardInterrupt:  # pragma: no cover
        return 130
    except SchemaError as exc:
        print(f"titan-mcp: schema error: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
