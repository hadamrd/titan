# titan-mcp — MCP server for the Titan REST API

`titan-mcp` is a first-party [Model Context Protocol](https://modelcontextprotocol.io)
server that mirrors **titan-server**'s `/api/v1/**` surface as MCP tools.
Every documented OpenAPI operation becomes one tool — name = the operation's
`operationId`, description = `summary + description`, arguments derived from
the operation's parameters and JSON request body.

The tool catalogue is **generated from the OpenAPI schema**, not hand-written,
so it stays in sync as titan-server evolves. A checked-in snapshot ships with
the package for offline / first-run use; a live snapshot is fetched via
`task titan-mcp:gen`.

This unblocks Claude Code / Cursor / codex-cli users who today either shell out
to `curl + jq` or write their own MCP client by hand.

> Tracking issue: [#1062](https://github.com/hadamrd/titan/issues/1062).

## Install

The package lives at `titan-mcp/` in this repo. It is a standard Python
project (PEP 621 `pyproject.toml`); install with any modern Python tool.

```bash
# from the repo root, in a venv
pip install -e ./titan-mcp

# or via pipx for a global CLI
pipx install ./titan-mcp
```

Requires Python 3.10+.

## Configure

`titan-mcp` reads two env vars:

| Variable          | Required | What it is                                                                                              |
|-------------------|----------|---------------------------------------------------------------------------------------------------------|
| `TITAN_BASE_URL`  | yes      | Base URL of the Titan controller (e.g. `https://titan.example.com`). Trailing slashes are stripped.     |
| `TITAN_TOKEN`     | yes      | Personal access token issued via the [PAT API](https://github.com/hadamrd/titan/pull/464). Sent as `Authorization: Bearer`. |
| `TITAN_OPENAPI_SCHEMA` | no  | Override the bundled OpenAPI snapshot with a custom path.                                               |

If either required variable is unset, **the server still starts** — tool calls
return a structured `{ok: false, error: "auth_required"|"base_url_required"}`
so the assistant can explain the misconfiguration in the chat instead of the
session crashing.

## Subcommands

```text
titan-mcp                    # alias for `titan-mcp serve`
titan-mcp serve              # speak MCP over stdio
titan-mcp diagnose           # print "generated N tools from M operations" + list
```

`diagnose` is the standard "is my schema producing the tools I expect?"
sanity check. The integration test asserts `N == M`.

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

Cursor and codex-cli use the same MCP server contract — same JSON works,
adjusted to each client's MCP config format.

## How tool generation works

Walking the OpenAPI document:

| OpenAPI shape                            | Surfaces as                                                       |
|------------------------------------------|-------------------------------------------------------------------|
| `GET /x` with `parameters[in=query]`     | tool with those names as optional arguments                       |
| `GET /x/{id}`                            | tool with `{id}` as a **required** argument; URL-encoded on call  |
| `POST/PATCH/PUT /x` with `application/json` body | tool with a `body` argument carrying the JSON payload     |
| `DELETE /x/{id}`                         | tool with `{id}` as a required argument                           |

**Skipped** (and reported by `diagnose`):
- operations with no `operationId`
- operations whose path template references an undeclared parameter
- request bodies with no `application/json` content (e.g. multipart upload)
- websocket / SSE endpoints (separate ticket; see e.g. `BuildLogsSse`)

**Hard error at startup** (refuses to serve):
- duplicate `operationId` between two operations — refuses to silently shadow
  one tool with another.

## Regenerating the bundled snapshot

```bash
export TITAN_BASE_URL=https://titan.example.com
export TITAN_TOKEN=tn_pat_...
task titan-mcp:gen
```

This fetches `${TITAN_BASE_URL}/q/openapi?format=JSON` and writes it to
`titan-mcp/src/titan_mcp/schema/openapi.json`. Commit the diff in a separate
PR so reviewers can see exactly which tools changed.

## Out of scope (for #1062)

- Schema → JSON-Schema-Draft 2020-12 conversion. We forward OpenAPI parameter
  schemas directly into MCP `inputSchema`. Sufficient for primitive arguments;
  may need a normalisation pass for nested `$ref`s in the future.
- WebSocket / SSE streaming endpoints — separate ticket.
- PyPI publishing — follow-up; this PR ships the package + install path.

## Tests

```bash
cd titan-mcp
python -m venv .venv && .venv/bin/pip install -e '.[test]'
.venv/bin/pytest
```

Coverage:
- `test_generator.py` — 5-shapes fixture, dup-`operationId`, missing
  `operationId`, undeclared path var, bad JSON, bundled-snapshot N==M.
- `test_server.py` — auth-missing, base-url-missing, 404 propagation,
  transport error, URL-encoded path params, JSON body threading, missing
  required path param.
- `test_cli.py` — `diagnose` summary, bundled-snapshot N==M, non-zero exit
  on duplicate-operationId schemas.
