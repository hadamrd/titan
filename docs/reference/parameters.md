# Build Parameters

How a pipeline declares the parameters it accepts, how a build supplies values, and how those values are referenced.

A pipeline declares its parameters in a root `parameters:` block. A build supplies values (via the API or a trigger); the bake resolves the two — applying defaults, enforcing required-ness, validating `choice` membership, and coercing types — into the effective `params` map. That map is read by `when:` conditions and `${{ params.* }}` references.

## Declaration

```yaml
parameters:
  - name: deployEnv
    type: choice
    choices: [dev, staging, prod]
    default: dev
  - name: runSmoke
    type: boolean
    default: true
  - name: branch
    type: string
    required: true
```

| Key | Required | Type | Notes |
|---|---|---|---|
| `name` | **yes** | string | the key under `params` |
| `type` | no | enum | `string` (default), `boolean`, `number`, `choice` |
| `default` | no | any | applied when the build supplies no value |
| `description` | no | string | documentation / UI |
| `required` | no | boolean | a value must be supplied (no default fallback) |
| `choices` | no | string or list | allowed values when `type: choice` |

## Resolution

At bake time, for each declared parameter:

1. If the build supplied a value, it is coerced to the declared type.
2. Otherwise the `default` is used.
3. A `required` parameter with no supplied value and no default fails the bake.
4. A `choice` value not in `choices` fails the bake.

Values supplied by a build that are **not declared** are rejected — this catches typos in trigger and submit configurations rather than silently dropping them. The same validation runs at the HTTP boundary when a build is triggered, so callers see the error immediately.

## Referencing parameters

Resolved parameters are available two ways:

- **In step arguments** — `${{ params.<name> }}`, deep-resolved through nested maps and lists wherever it appears in a step's arguments.

  ```yaml
  - sh: "deploy --env ${{ params.deployEnv }} --branch ${{ params.branch }}"
  ```

- **In `when:` conditions** — as `params.<name>` inside the CEL expression.

  ```yaml
  - stage: Smoke
    when: "params.runSmoke == true"
    steps:
      - sh: make smoke
  ```

## Build parameters vs. template params

These are two distinct mechanisms — do not confuse them:

| | Build parameters | Template params |
|---|---|---|
| Declared in | root `parameters:` | a template file's `params:` |
| Supplied by | build trigger / API | the `use: { with: … }` call |
| Types | `string`, `boolean`, `number`, `choice` | `string`, `integer`, `boolean` |
| Referenced as | `${{ params.<name> }}` | `${param.<name>}` |
| Resolved by | the bake (`ParameterResolver`) | the parser (template inlining) |

See [pdl.md](pdl.md#templates-use) for template params.
