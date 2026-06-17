# Reference

Authoritative, code-verified reference for Titan's pipeline language, built-in steps, configuration, schema, and extension points.

| Document | Covers |
|---|---|
| [pdl.md](pdl.md) | Pipeline Definition Language — every root key, stage/step shape, and grammar scope. |
| [steps.md](steps.md) | Built-in step library — each shipped step and its parameters. |
| [parameters.md](parameters.md) | Build parameters — declaration, types, resolution, and reference syntax. |
| [configuration.md](configuration.md) | Server and worker configuration — every environment variable and setting. |
| [database.md](database.md) | Database schema — tables, columns, and migrations. |
| [spi.md](spi.md) | Extension SPIs — step, trigger, secrets, and artifact-store interfaces and discovery. |
| [mcp.md](mcp.md) | The `titan-mcp` server — the REST API surfaced as Model Context Protocol tools. |

Reference docs describe the engine **as it ships**. Where the code and an older design note disagree, the code wins.
