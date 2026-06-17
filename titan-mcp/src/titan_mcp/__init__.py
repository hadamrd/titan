"""titan-mcp — Model Context Protocol server for the Titan REST API."""

__version__ = "0.1.0"

from titan_mcp.generator import (
    GeneratedTool,
    SchemaError,
    generate_tools,
    load_schema,
)

__all__ = ["GeneratedTool", "SchemaError", "generate_tools", "load_schema", "__version__"]
