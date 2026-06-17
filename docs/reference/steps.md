# Built-in Steps

Every step that ships with Titan, with its arguments. The step type is the descriptor key written in the pipeline (see [pdl.md](pdl.md#steps)).

Steps fall into two execution classes:

- **Worker steps** run on an agent, dispatched from the task queue. They are discovered through the [step SPI](spi.md#step-spi).
- **Control-plane steps** never reach a worker — the orchestrator handles them durably on the server (timers, approvals, build metadata).

A *shorthand* argument lets a step take a bare scalar: `sh: make test` is equivalent to `sh: { script: "make test" }`.

## Worker steps

Shipped in `titan-worker` (the `SocleStepHandlerProvider` family). `R` = required, `O` = optional.

### `sh`

Runs a shell script on the agent, streaming stdout/stderr. A non-zero exit fails the step. Shorthand → `script`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `script` | string | R | — |

### `script`

Runs an inline script body in a runtime. Groovy bodies may call `sh`/`echo`/`error`/`setOutput` and staged `vars/<name>.groovy` library functions.

| Param | Type | R/O | Default |
|---|---|---|---|
| `runtime` | string | R | — (`groovy`) |
| `body` | string | R | — |
| `libraries` | list | O | — |

### `git`

Clones a Git repository via the git CLI (shallow by default). Shorthand → `url`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `url` | string | R | — |
| `branch` | string | O | repo default branch |
| `shallow` | boolean | O | `true` |
| `credentialsId` | string | O | — |

### `checkout`

Generic SCM checkout — same implementation as `git`. Shorthand → `url`. Arguments identical to [`git`](#git).

### `gitTag`

Creates a Git tag (annotated when `message` is set) and optionally pushes it. Shorthand → `tag`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `tag` | string | R | — (supports `${VAR}`) |
| `message` | string | O | — (annotated when present) |
| `ref` | string | O | `HEAD` |
| `push` | boolean | O | `true` |
| `remote` | string | O | `origin` |
| `credentialsRef` | string | O | — |

### `writeFile`

Writes text to a workspace file, creating parent directories.

| Param | Type | R/O | Default |
|---|---|---|---|
| `file` | string | R | — |
| `text` | string | R | — |

### `readFile`

Reads a workspace file and publishes its content as a named output. Shorthand → `file`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `file` | string | R | — |
| `output` | string | O | `content` |

### `fileExists`

Publishes a boolean output indicating whether a path exists. Shorthand → `file`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `file` | string | R | — |
| `output` | string | O | `exists` |

### `deleteDir`

Recursively deletes a workspace directory. Shorthand → `dir`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `dir` | string | O | `.` (whole workspace) |

### `archiveArtifacts`

Archives workspace files matching an Ant-style glob through the configured [artifact store](spi.md#artifactstore-spi).

| Param | Type | R/O | Default |
|---|---|---|---|
| `artifacts` | string | R | — (Ant include glob(s)) |
| `excludes` | string | O | — |
| `allowEmptyArchive` | boolean | O | `false` |
| `fingerprint` | boolean | O | `false` |
| `caseSensitive` | boolean | O | `true` |
| `followSymlinks` | boolean | O | `true` |

### `junit`

Parses JUnit / surefire XML and publishes a pass/fail/skip tally. A test failure fails the step unless `skipMarkingBuildUnstable` is set. Shorthand → `testResults`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `testResults` | string | R | — (Ant glob(s)) |
| `allowEmptyResults` | boolean | O | `false` |
| `skipMarkingBuildUnstable` | boolean | O | `false` |

### `httpRequest`

Makes one HTTP(S) request (with `${VAR}` expansion) and publishes the response as outputs. Shorthand → `url`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `url` | string | R | — |
| `method` | string | O | `GET` (GET/POST/PUT/PATCH/DELETE/HEAD) |
| `headers` | object | O | — |
| `body` | string | O | — |
| `jsonBody` | object | O | — |
| `bodyFile` | string | O | — (overrides `body`) |
| `contentType` | string | O | — |
| `validResponseCodes` | string | O | `100:399` (e.g. `200`, `201,301:303`) |
| `outputFile` | string | O | — |
| `outputs` | object | O | — (output name → JSON path) |
| `timeoutSeconds` | number | O | `30` |
| `insecureTls` | boolean | O | `false` |

There is no `expectStatus` argument — use `validResponseCodes`.

### `k8sApply`

Applies a manifest with `kubectl apply -f` (idempotent). Shorthand → `manifest`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `manifest` | string | R | — (workspace-relative path) |
| `namespace` | string | O | — |
| `wait` | boolean | O | `true` |
| `timeout` | string | O | `5m` |

### `setOutput`

Publishes named state readable by downstream steps. Use either the single-pair or the map form.

| Param | Type | R/O |
|---|---|---|
| `name` | string | O |
| `value` | string | O |
| `values` | object (map) | O |

### `error`

Deliberately fails the node with a message. Shorthand → `message`.

| Param | Type | R/O |
|---|---|---|
| `message` | string | R |

### `echo`

Prints a message to the build console. Shorthand → `message`.

| Param | Type | R/O |
|---|---|---|
| `message` | string | R |

### `libraryCall`

Calls one `vars/<file>.groovy::<method>(Map)` from a shared library as a DAG node. Usually written via the dotted `alias.method` form (see [pdl.md](pdl.md#libraries--library-calls)), which the parser rewrites into this step.

| Param | Type | R/O |
|---|---|---|
| `library` | string | R (`<git-url>@<ref>`) |
| `file` | string | R |
| `method` | string | R |
| `libraryCredential` | string | O |
| `args` | map | O |

## Container-manifest steps (Tier-1)

A declarative container step defined entirely by a `*.titanstep.yaml` manifest dropped into the worker's steps directory (`TITAN_STEPS_DIR`, default `./steps/`). The type string, parameters, display name, and help all come from the manifest — no jar, no classloader. Each manifest's `params:` entries (name / type / required) become the step's arguments. See [configuration.md](configuration.md) and [spi.md](spi.md#step-spi).

## Control-plane steps

Handled by the orchestrator on the server; they never dispatch to a worker and have no agent footprint.

### `sleep`

Durable timer — pauses the build for a relative duration. Shorthand `sleep: 5m` (bare number = seconds; suffixes `s`/`m`/`h`/`d`), or `{ time, unit }`. Capped at one year.

| Param | Type | Default |
|---|---|---|
| `value` (scalar) | duration | — |
| `time` | number | — |
| `unit` | string | `SECONDS` |

### `waitUntil`

Durable timer until an absolute ISO-8601 instant; a past instant resolves immediately. Capped at one year out. Shorthand → `value`.

| Param | Type |
|---|---|
| `value` | ISO-8601 instant |

### `approval`

Pauses for human approval, creating a pending decision that auto-rejects on timeout. Shorthand → `prompt`.

| Param | Type | R/O | Default |
|---|---|---|---|
| `prompt` | string | R | — |
| `approvers` | list | O | — (empty = anyone) |
| `timeout` | duration | O | `24h` (max 1y) |

### `setBuildName`

Sets the build's display name (supports `${…}` reference expansion). Shorthand → `name`.

| Param | Type | R/O |
|---|---|---|
| `name` | string | R |

## Discovery

Worker steps are loaded via the JDK `ServiceLoader` (`StepHandlerProvider`); third-party steps are added by dropping jars or `*.titanstep.yaml` manifests into `TITAN_STEPS_DIR`. See [spi.md](spi.md#step-spi) to write a step.
