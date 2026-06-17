# Pipeline Definition Language

The complete reference for `titan-pipeline.yml` — every root key, the stage and step model, and every grammar scope the parser accepts.

A Titan pipeline is a YAML document. The grammar is defined in code (`TitanGrammar`, `WhenGrammar`, `TriggerGrammar`) and the JSON Schema published with the engine is generated from it — the grammar classes are the single source of truth. The parser is **strict**: unknown keys, wrong types, and misspelled scopes are rejected at parse time, not silently ignored.

> Spelling matters. Multi-word keys are **camelCase** (`dependsOn`, `onFailure`, `sshAgent`, `buildRetention`). The only snake_case keys are `fail_fast` (matrix), `on_overflow` (concurrency), and `files_changed` (step `when`). The `depends_on`, `on_failure`, and `ssh_agent` spellings are **not** accepted.

## Document structure

The pipeline body may sit at the document root (canonical) or be nested under a single `titan:` key (legacy wrapper — no sibling keys allowed alongside `titan:`).

```yaml
parameters:
  - name: deployEnv
    type: choice
    choices: [dev, staging, prod]
    default: dev
env:
  CI: "true"
stages:
  - stage: Build
    steps:
      - sh: make build
  - stage: Deploy
    dependsOn: [Build]
    when: "params.deployEnv == 'prod'"
    steps:
      - sh: "make deploy ENV=${{ params.deployEnv }}"
```

## Root keys

| Key | Required | Type / shape | Notes |
|---|---|---|---|
| `stages` | **yes** | array (≥1) of stage nodes | the build DAG |
| `agent` | no | string | default agent label for all stages |
| `failurePolicy` | no | enum `blockOnFailure` \| `continueOnFailure` | |
| `parameters` | no | array of [parameter](parameters.md) | declared build parameters |
| `env` | no | map string→string | merged into every step's environment |
| `triggers` | no | array of trigger | see [Triggers](#triggers) |
| `libraries` | no | map alias→coordinate | see [libraries / library calls](#libraries--library-calls) |
| `credentials` | no | array of [credential binding](#credentials) | applied to every step |
| `timeout` | no | duration string | pipeline-wide deadline |
| `notify` | no | array of [notify hook](#notify) | |
| `concurrency` | no | int or object | see [concurrency](#concurrency) |
| `priority` | no | enum `high` \| `normal` \| `low` | queue priority (bare ints rejected) |
| `buildRetention` | no | `{ keepLast: int≥0 }` | `0` keeps all |
| `include` | no | array of path or object | composition; see [include](#include) |

There is no `options`, `retention` (use `buildRetention`), `includes` (use `include`), or `parallel` key — concurrency is expressed through the DAG, not a `parallel` block.

### Durations

Duration values (`timeout`, `retry` backoff, `sleep`) are strings: a bare number is seconds, or a number with a suffix — `s`, `m`, `h`, `d` (e.g. `30s`, `5m`, `2h`, `1d`).

## Stage nodes

Each `stages[]` entry is a discriminated union — exactly one of `stage:`, `gate:`, or `precondition:`.

### `stage`

| Key | Required | Shape | Notes |
|---|---|---|---|
| `stage` | **yes** | string | stage name (slugified to its node id) |
| `steps` | no | array of [step](#steps) | |
| `agent` | no | string | overrides the pipeline `agent` |
| `image` | no | string | default container image for the stage's steps |
| `dependsOn` | no | string or list | upstream stages that must succeed first |
| `onFailure` | no | string or list | run only when a named upstream **fails** (mutually exclusive with `dependsOn`) |
| `when` | no | string (CEL) | run condition; see [when](#when) |
| `env` | no | map | merged with pipeline/step env |
| `credentials` | no | array | bindings flattened onto the stage's steps |
| `sshAgent` | no | string or list | credential ids; applies to `sh` steps only |
| `retry` | no | int or object | default retry policy for the stage's steps |
| `timeout` | no | duration | default step timeout |
| `matrix` | no | object | fan the stage into a grid; see [matrix](#matrix) |
| `each` | no | object | fan the stage over a list; see [each](#each) |
| `use` | no | object | inline a template; see [templates](#templates-use) |
| `notify` | no | array | stage-terminal hooks |

The stage identifier is `stage`, not `name`.

### `gate`

A manual checkpoint in the DAG.

| Key | Required | Shape | Default |
|---|---|---|---|
| `gate` | **yes** | string | — |
| `requiresApproval` | no | boolean | `true` |
| `approvers` | no | string or list | — |
| `dependsOn` | no | string or list | — |

### `precondition`

A boolean guard that must pass before downstream stages run.

| Key | Required | Shape |
|---|---|---|
| `precondition` | **yes** | string |
| `expression` | **yes** | string (CEL) |
| `dependsOn` | no | string or list |

## Steps

A step is a YAML map with **exactly one descriptor key** (the step type — `sh`, `git`, `httpRequest`, `k8sApply`, …) plus optional sibling scope keys. Steps are **not** discriminated by a `type:` field; the descriptor key *is* the step type.

```yaml
steps:
  - sh: make test               # scalar → folded into the step's shorthand arg
  - httpRequest:                 # object → argument map
      url: https://api.internal/deploy
      method: POST
  - checkout:                    # null → empty args
```

- A **scalar** value is folded into the descriptor's shorthand argument (e.g. `sh: make test` → `script: "make test"`).
- An **object** value is the argument map.
- A **null** value means empty arguments.

The full set of step types and their arguments is in [steps.md](steps.md). Step argument values support `${{ … }}` reference substitution (see [parameters.md](parameters.md)).

### Step-level scopes

These scope keys may appear as siblings of the descriptor key **on a step**: `image`, `credentials`, `sshAgent`, `retry`, `timeout`, `when`, `env`. A step-level scope overrides the same scope set at stage or pipeline level.

```yaml
- sh: ./flaky.sh
  retry: 3
  timeout: 2m
  when:
    branch: main
```

The stage-only scopes (`dependsOn`, `onFailure`, `matrix`, `each`, `use`, `notify`) are **not** valid on a step.

## Grammar scopes

### `matrix`

Fans a stage into a grid of cells, one per combination of axis values.

| Key | Required | Shape | Default |
|---|---|---|---|
| `axes` | **yes** | map name→list of scalars | — |
| `exclude` | no | array of cell maps | — |
| `include` | no | array of cell maps (must name every axis) | — |
| `maxParallel` | no | int ≥ 1 | unbounded |
| `fail_fast` | no | boolean | **`true`** |

Each cell receives:
- `${matrix.<axis>}` substitution in step args, runtime, body, and env.
- An environment variable **`MATRIX_<AXIS>`** per axis (axis name upper-cased, non-alphanumerics → `_`; e.g. axis `os` → `MATRIX_OS`). User-set env wins (`putIfAbsent`).

Matrices are capped at 100 cells.

```yaml
- stage: Test
  matrix:
    axes:
      os: [linux, mac]
      jdk: ["17", "21"]
    exclude:
      - { os: mac, jdk: "17" }
    fail_fast: false
  steps:
    - sh: ./test.sh --os $MATRIX_OS --jdk $MATRIX_JDK
```

### `each`

Fans a stage over a single list.

| Key | Required | Shape | Default |
|---|---|---|---|
| `var` | **yes** | identifier (`[A-Za-z_]\w*`; not `each`/`matrix`/`env`/`params`) | — |
| `in` | **yes** | non-empty list of scalars | — |
| `maxParallel` | no | int ≥ 1 | unbounded |

Cells get `${each.<var>}` substitution. `each` injects no environment variables and has no `fail_fast` key (fail-fast is always on).

```yaml
- stage: Deploy
  each:
    var: region
    in: [us-east, eu-west]
  steps:
    - sh: "deploy --region ${each.region}"
```

### `when`

A run condition. Behaviour depends on where it appears.

- **Stage-level `when:`** — a free-form CEL expression string only. It reads `params.*` and the build context.
  ```yaml
  - stage: Deploy
    when: "params.deployEnv == 'prod'"
  ```
- **Step-level `when:`** — either a CEL string, or a structured object with **exactly one** of:

  | Key | Shape | Meaning |
  |---|---|---|
  | `branch` | glob string | run on matching branch (e.g. `main`, `release/*`) |
  | `previous` | enum `success` \| `failure` \| `always` | run based on the previous step's outcome |
  | `files_changed` | non-empty list of globs | run when matching files changed |

  ```yaml
  - sh: ./deploy.sh
    when:
      branch: main
  ```

There are no structured `allOf` / `anyOf` / `not` / `equals` / `expression` operators — boolean composition is done in the CEL string form.

### `retry`

Re-runs a failed step.

Shorthand: `retry: 3` (max attempts). Object form:

| Key | Shape | Default |
|---|---|---|
| `maxAttempts` | int ≥ 1 | `1` |
| `backoff.initial` | duration | `10s` |
| `backoff.multiplier` | number ≥ 1 | `2.0` |
| `backoff.max` | duration | `5m` |
| `retryableExitCodes` | list of ints | all non-zero |

```yaml
- sh: ./flaky.sh
  retry:
    maxAttempts: 5
    backoff: { initial: 5s, multiplier: 2.0, max: 1m }
    retryableExitCodes: [42]
```

A step-level `retry` wins over a stage-level one.

### `onFailure`

Declares a stage that runs only when a named upstream stage **fails** (cleanup / notification handlers). Value is a string or list of upstream stage names; cannot self-reference and is mutually exclusive with `dependsOn`.

```yaml
- stage: Notify-On-Failure
  onFailure: [Build, Test]
  steps:
    - sh: ./alert.sh
```

### `sshAgent`

Loads SSH credentials into an ssh-agent for the duration of the stage's (or step's) `sh` steps. Value is a credential id or list of ids. Applies to `sh` steps only.

```yaml
- stage: Deploy
  sshAgent: [deploy-key]
  steps:
    - sh: ssh deploy@host ./run.sh
```

### `credentials`

Binds stored credentials into environment variables for the scope. An array of bindings:

| Key | Required | Notes |
|---|---|---|
| `id` | **yes** | credential id |
| `type` | **yes** | `usernamePassword` \| `string` \| `file` \| `sshKey` |
| `usernameVariable`, `passwordVariable` | for `usernamePassword` | env var names |
| `variable` | for `string` | env var name |
| `keyFileVariable`, `passphraseVariable` | for `file`/`sshKey` | env var names |

```yaml
credentials:
  - id: docker-hub
    type: usernamePassword
    usernameVariable: DOCKER_USER
    passwordVariable: DOCKER_PASS
```

Bindings may be set at pipeline, stage, or step level and are flattened onto the affected steps.

### `env`

A map of string→string. May appear at pipeline, stage, or step level; merged at dispatch (step wins, then stage, then pipeline). A value of the form `secret:<id>` resolves a stored secret at runtime.

```yaml
env:
  REGION: us-east
  TOKEN: "secret:ci-token"
```

### `concurrency`

Limits how many builds of the pipeline run at once. Either an int (the max) or an object:

| Key | Required | Shape | Default |
|---|---|---|---|
| `max` | **yes** | int ≥ 1 | — |
| `on_overflow` | no | `queue` \| `cancel_oldest` \| `cancel_pending` | `queue` |

```yaml
concurrency:
  max: 1
  on_overflow: cancel_oldest
```

### `notify`

Terminal notifications. May appear at pipeline or stage level. An array of hooks:

| Key | Shape | Notes |
|---|---|---|
| `type` | `webhook` \| `slack` | |
| `on` | list of `success` \| `failure` \| `recovery` \| `always` | when to fire |
| `url` | string | webhook target |
| `channel` | string | slack channel |
| `credentialsId` | string | credential for the hook |

```yaml
notify:
  - type: slack
    on: [failure, recovery]
    channel: "#ci"
    credentialsId: slack-token
```

### `priority`

Queue priority for the build: `high` (10), `normal` (0), or `low` (-10). Bare integers are rejected.

### `buildRetention`

`{ keepLast: <int≥0> }` — keep at most N most-recent builds of the pipeline; `0` keeps all.

### `include`

Composes external fragments into the pipeline before parsing. An array, each entry either a repo-relative path string or an object:

| Key | Required |
|---|---|
| `repo` | **yes** |
| `ref` | **yes** |
| `path` | **yes** |
| `credential` | no |

Includes are inlined pre-parse: list-valued keys (`stages`, `parameters`) concatenate; map-valued keys (`libraries`, `env`) shallow-merge with the main document winning on conflicts.

## Templates (`use`)

Inlines a reusable stage template from a repo-relative file, passing arguments through `with:`.

```yaml
- use:
    from: templates/deploy.yml
    with:
      service: api
      replicas: 3
```

The template file declares `params:` and `steps:`. Each param declaration accepts `required`, `type` (`string` \| `integer` \| `boolean`), and `default`. Inside the template, `${param.<name>}` is substituted with the resolved value. Unknown `with:` keys and missing required params are rejected at parse time.

> Template params (`${param.X}`, types `string`/`integer`/`boolean`) are a separate, narrower mechanism from pipeline build parameters (`${{ params.X }}`, types `string`/`boolean`/`number`/`choice`). See [parameters.md](parameters.md).

## Libraries / library calls

A shared-library alias declared at the root maps to a Git coordinate. A dotted step `alias.method` is rewritten into a `libraryCall` step that invokes `vars/<file>.groovy::<method>(Map)` from that library.

```yaml
libraries:
  acme: "https://git.internal/acme-lib@main"
  secured:
    url: "https://git.internal/secured-lib"
    credential: lib-token
stages:
  - stage: Build
    steps:
      - acme.notify:
          message: "build started"
```

A library coordinate is either a `<url>@<ref>` string or an object with `url` (required) and `credential`.

## Triggers

A `triggers:` array on the root declares how builds start. Each entry is one of:

| Entry | Shape |
|---|---|
| `cron` | a cron string |
| `github` / `gitlab` / `bitbucket` | `{ branches: string\|list, events: string\|list, credentialsId: <required> }` |

```yaml
triggers:
  - cron: "H/15 * * * *"
  - github:
      branches: [main, "release/*"]
      events: [push, pull_request]
      credentialsId: gh-app
```

See [spi.md](spi.md#trigger-spi) for adding new trigger types.

## See also

- [steps.md](steps.md) — the built-in step library
- [parameters.md](parameters.md) — build parameters and reference syntax
- [spi.md](spi.md) — extending steps, triggers, secrets, and artifact stores
