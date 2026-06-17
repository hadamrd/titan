# titan-mcp

First-party [Model Context Protocol](https://modelcontextprotocol.io) server for
[Titan](https://github.com/hadamrd/titan), the modern cloud-native CI/CD product.

Every documented endpoint on titan-server's REST API at `/api/v1/**` is exposed
as an MCP tool. Claude Code / Cursor / codex-cli users can drive a Titan
controller in natural language — "list the last 10 failed builds for
`platform/api-gateway`" — instead of stitching together `curl + jq`.

The tool catalogue is **generated from the OpenAPI schema**, so it stays in
sync as the API evolves. Run `task titan-mcp:gen` to refresh the snapshot
against a live controller.

## Install

```bash
pip install -e ./titan-mcp
export TITAN_BASE_URL=https://titan.example.com
export TITAN_TOKEN=<personal-access-token>   # see PR #464
titan-mcp diagnose
```

## Claude Code config

```json
{
  "mcpServers": {
    "titan": {
      "command": "titan-mcp",
      "env": {
        "TITAN_BASE_URL": "https://titan.example.com",
        "TITAN_TOKEN": "tn_pat_..."
      }
    }
  }
}
```

See [`docs/reference/mcp.md`](../docs/reference/mcp.md) for the full reference.
