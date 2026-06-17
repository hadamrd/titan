---
name: add-pdl-step
description: >-
  Add a step (or grammar scope) to the Titan PDL — the declarative pipeline
  language (titan-pipeline.yml). TRIGGER when the user wants to add, implement,
  or scaffold a new built-in pipeline step / step handler, or a new grammar
  scope/keyword, for the Titan engine. Gives the exact file map, the canonical
  template to copy, and the test recipe. SKIP for Tier-1/Tier-2 third-party
  step extensions (those are a manifest/jar dropped in TITAN_STEPS_DIR, not a
  code change) and for non-Titan plugin work.
---

# Adding a step to the Titan PDL

## The one insight that makes this fast

A built-in step is **schema-lenient** (design/42 §7): the grammar, the parser,
and the pipeline model are **step-agnostic** — they read `descriptorId: args`
generically and never change when you add a step. A step's truth lives on the
**worker**, in a `StepHandler`. So adding a step is **~3 files**:

1. a new `StepHandler` (the logic),
2. one line registering it,
3. a TCK-based test.

Do **not** touch `TitanGrammar.java`, `TitanYamlParser.java`, `StepModel.java`,
or `titan-pipeline.schema.json` for a step. If you find yourself editing those,
you are either adding a *scope* (see Path B) or doing it wrong.

---

## Path A — add a built-in step (the 95% case)

### 1. Copy the canonical template

The simplest complete step is **`DeleteDirStepHandler`**. Copy both files and
rename `DeleteDir` → `YourStep` throughout (class name, `descriptorId`, help):

- handler — `titan-worker/src/main/java/io/adaptiq/titan/worker/step/builtin/DeleteDirStepHandler.java`
- test    — `titan-worker/src/test/java/io/adaptiq/titan/worker/step/builtin/DeleteDirStepHandlerTest.java`

It is the minimal shape: only JDK deps, declares a scalar shorthand, idempotent,
and every TCK test passes with no extra setup. **The template is the source of
truth for the exact `StepHandler` / `StepDescriptor` / `ParamSpec` API** — read
it rather than guessing signatures.

### 2. Implement the handler

A `StepHandler` (SPI in `titan-step-api`, package
`io.adaptiq.titan.worker.step`) has three methods:

- `descriptorId()` — the YAML keyword, e.g. `"myStep"`. **Must be globally
  unique** (see Gotchas).
- `descriptor()` — a `StepDescriptor`: id, display name, help, the
  `List<ParamSpec>` of arguments, and an optional *scalar shorthand* key (the
  key that `myStep: <value>` shorthand maps to — `sh:`/`deleteDir:` use this).
- `execute(StepRequest)` — the logic; returns `StepResult.success()` /
  `.failed(msg)` / `.ofExitCode(n)`.

`StepRequest` gives you `arguments()`, `workDir()`, `env()`, `executor()`,
`log()` (already secret-masking), `outputs()`, `artifacts()`, `testReports()`.
Arguments are validated against `ParamSpec` **before** `execute()` — a handler
never sees invalid input.

### 3. Register it

Add one line to the socle provider's handler list:

- `titan-worker/src/main/java/io/adaptiq/titan/worker/step/SocleStepHandlerProvider.java`
  — add `new YourStepHandler()` to the `List.of(...)` in `handlers(...)`.

That is the whole registration — discovery is `ServiceLoader`-based and this
provider is already wired.

### 4. Test

Your test **extends `StepHandlerTck`**
(`titan-step-api/src/test/java/io/adaptiq/titan/worker/step/StepHandlerTck.java`)
— override `newHandler()`, `validArguments()`, `newExecutor()` (usually
`new LocalProcessExecutor()`), and add `@Test`s for your step's behaviour. The
TCK contributes the contract tests for free.

Add a parse case to
`src/test/java/io/adaptiq/titan/flow/parser/TitanYamlParserTest.java`
proving `myStep: {...}` parses to a `StepModel` with the right `descriptorId`
and arguments.

Run (never the full suite — 20+ min, forbidden):

```
mvn test -Dtest=YourStepHandlerTest
mvn test -Dtest=TitanYamlParserTest
mvn test -Dtest=TitanSchemaGenerationTest,GrammarSchemaContractTest
```

The last two should pass **unchanged** — if they fail, you touched grammar/schema
when you shouldn't have.

---

## Path B — add a grammar scope/keyword (rare)

Only for a genuinely new pipeline *keyword* (like `when:`, `retry:`,
`credentials:`), not a step. Then:

1. Create `NewScope.java` implementing `StepScope` in
   `titan-pipeline-model/src/main/java/io/adaptiq/titan/flow/parser/`
   — copy an existing scope (e.g. `RetryScope.java`). The scope owns its own
   `schema()` / `stepSchema()`.
2. Register it in the `SCOPES` list in `TitanYamlParser.java`.
3. Regenerate the JSON schema: run `TitanSchemaGenerator.main()`. Do **not**
   hand-edit `src/main/resources/io/adaptiq/titan/schemas/titan-pipeline.schema.json`
   — `TitanSchemaGenerationTest` fails on drift and tells you to regenerate.

---

## Gotchas

- **Duplicate `descriptorId` is a hard boot error** — the worker refuses to
  start (design/42 §4.3). Grep the socle + any installed steps first.
- **Idempotency.** A reaped task re-runs from the start (design/30). Make
  `execute()` idempotent — deleting an absent dir is success, persisted data is
  upserted. Persist via `request.artifacts()` / `request.testReports()`.
- **Schema stays lenient.** A built-in step's args are *not* projected into
  `titan-pipeline.schema.json` — the worker validates at runtime. Don't try to
  add the step to the schema.
- **No Jelly, no `${%key}` i18n** for steps yet — step UI is a future chunk.
  Don't add localization markers to help text.
- **Masking is automatic** — `request.log()` is a masking sink; never hold or
  re-mask secrets yourself.

---

## Source of truth

`design/41-32e-builtin-step-library.md`, `design/42-titan-step-extensions.md`,
and the design/47 single-source-grammar doc.

> This runbook was mapped while the PDL grammar was under active rework
> (single-source grammar shipped, design/48 parser-strictness in flight). Paths
> are current as of 2026-05-17; if `TitanGrammar` / `TitanYamlParser` /
> `TitanSchemaGenerator` have since moved, re-verify Path B against them.
