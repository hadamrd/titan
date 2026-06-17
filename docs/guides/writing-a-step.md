# Writing a step

How to add a built-in PDL step to the Titan engine.

A pipeline step (`sh`, `git`, `archiveArtifacts`, …) is the unit of work in a
Titan pipeline. This guide covers adding a **built-in** step that ships with the
engine. To ship a step as a drop-in jar or manifest instead — without changing
engine code — see [Extending Titan](extending-titan.md).

> Adding a step is also a one-command scaffold: run the `add-pdl-step` skill,
> which generates the files below from the canonical template.

## The key insight

The grammar, parser, and pipeline model are **step-agnostic**: they read
`descriptorId: args` generically and never change when you add a step. A step's
behaviour lives on the worker, in a `StepHandler`. So a new step is **three
files**:

1. a `StepHandler` (the logic),
2. one line registering it,
3. a test.

Do **not** touch the grammar, the YAML parser, the pipeline model, or
`titan-pipeline.schema.json` to add a step. A built-in step's arguments are
validated by the worker at runtime, not projected into the schema. (Editing
those files means you are adding a new grammar *scope* — a rare case; copy an
existing `StepScope` and regenerate the schema.)

## 1. Copy the template

The simplest complete step is `DeleteDirStepHandler` — JDK-only deps, a scalar
shorthand, idempotent, and every contract test passes with no extra setup. Copy
both files and rename `DeleteDir` → `YourStep` throughout:

- handler — `titan-worker/src/main/java/io/adaptiq/titan/worker/step/builtin/DeleteDirStepHandler.java`
- test — `titan-worker/src/test/java/io/adaptiq/titan/worker/step/builtin/DeleteDirStepHandlerTest.java`

The template is the source of truth for the exact `StepHandler` /
`StepDescriptor` / `ParamSpec` API — read it rather than guessing signatures.

## 2. Implement the handler

A `StepHandler` (SPI in `titan-step-api`, package
`io.adaptiq.titan.worker.step`) has three methods:

```java
public final class GreetStepHandler implements StepHandler {

  @Override
  public String descriptorId() {
    return "greet"; // the YAML keyword — must be globally unique
  }

  @Override
  public StepDescriptor descriptor() {
    return new StepDescriptor(
        "greet",
        "Greet",
        "Prints a greeting to the build log.",
        List.of(ParamSpec.required("name", "string", "Who to greet.")),
        "name"); // optional scalar shorthand: `greet: <value>` maps to `name`
  }

  @Override
  public StepResult execute(StepRequest request) throws Exception {
    request.log().system("Hello, " + request.argString("name", "world"));
    return StepResult.success();
  }
}
```

`StepRequest` gives you `arguments()`, `workDir()`, `env()`, `executor()`,
`log()` (already secret-masking), `outputs()`, `artifacts()`, `testReports()`.
Arguments are validated against the `ParamSpec` list **before** `execute()` runs,
so a handler never sees invalid input. Return `StepResult.success()`,
`.failed(msg)`, or `.ofExitCode(n)`.

**Make `execute()` idempotent.** A reaped task re-runs from the start, so
deleting an absent directory is a success and persisted data is upserted.

## 3. Register it

Add one line to the socle provider's handler list:

`titan-worker/src/main/java/io/adaptiq/titan/worker/step/SocleStepHandlerProvider.java`
— add `new GreetStepHandler()` to the `List.of(...)` in `handlers(...)`.

That is the whole registration. Discovery is `ServiceLoader`-based and the
provider is already wired.

## 4. Test

Your test extends `StepHandlerTck`
(`titan-step-api/src/test/java/io/adaptiq/titan/worker/step/StepHandlerTck.java`):
override `newHandler()`, `validArguments()`, and `newExecutor()` (usually
`new LocalProcessExecutor()`), then add `@Test`s for your step's behaviour. The
TCK contributes the contract tests for free.

Add a parse case to `TitanYamlParserTest` proving `greet: {...}` parses to a
`StepModel` with the right `descriptorId` and arguments.

Run the scoped tests (never the full suite):

```bash
./gradlew -p titan-worker test --tests GreetStepHandlerTest
./gradlew -p titan-pipeline-model test --tests TitanYamlParserTest
```

The schema/grammar contract tests should pass unchanged — if they fail, you
touched the grammar or schema when you should not have.

## Gotchas

- **Duplicate `descriptorId` is a hard boot error** — the worker refuses to
  start. Grep the socle and any installed steps first.
- **The schema stays lenient.** Do not try to add the step to
  `titan-pipeline.schema.json`; the worker validates args at runtime.
- **Masking is automatic.** `request.log()` is a masking sink; never hold or
  re-mask secrets yourself.

## See also

- [Extending Titan](extending-titan.md) — ship a step as a drop-in jar/manifest.
- [Testing](testing.md) — the test tiers and how to run them.
