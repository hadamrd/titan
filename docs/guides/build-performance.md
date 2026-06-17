# Build performance

Titan ships with FAANG-grade Gradle defaults baked into `gradle.properties` —
no flags to remember, fast by default.

## What's enabled

| Lever | Effect |
|---|---|
| `org.gradle.parallel=true` | Independent project tasks run in parallel up to `workers.max=8`. |
| `org.gradle.caching=true` | Local build cache ON. Task outputs (compileJava, test, jar, …) are reused across runs/branches/clean checkouts as long as the input hash matches. |
| `org.gradle.configureondemand=true` | Only configures projects in the requested task graph. Skips configuration work for the 90% of modules a single invocation doesn't touch. |
| `org.gradle.daemon=true` | Daemon stays alive between invocations — JIT'd classes stay hot. |
| `org.gradle.jvmargs=-Xmx4g …` | 4 GB heap. Quarkus augmentation + JDBI SqlObject bytecode-gen + large test classpaths peak around 2.5 GB. |
| `org.gradle.vfs.watch=true` | Virtual file system watcher. Gradle doesn't `stat()` every input on every invocation. |

## Measured numbers

Baseline machine: WSL2 on a 16-core/16 GB dev box.

| Scenario | Wall time |
|---|---|
| Cold (clean checkout, no caches) | **~3m 45s** |
| Warm + 1 file changed in titan-server | **~4s** |
| Warm + no changes | **~3s** |

Most of the cold→warm jump is the build cache; the warm-second-run improvement
on top is the Gradle daemon staying alive with caches loaded.

## Configuration cache (off by default — TODO)

The configuration cache is supported by Gradle and adds another 1-3s on warm
runs by serialising the task graph between invocations. It's currently OFF
because the convention plugin's Test-task classpath assignment isn't config-
cache-compatible. Switch on per-invocation with `--configuration-cache` once
that's fixed.

## Remote build cache (env-gated)

`settings.gradle.kts` declares an HTTP remote build cache backend that
activates when `TITAN_BUILD_CACHE_URL` is set:

```bash
export TITAN_BUILD_CACHE_URL=https://build-cache.example.com/
export TITAN_BUILD_CACHE_USERNAME=…   # optional
export TITAN_BUILD_CACHE_PASSWORD=…   # optional
```

Only CI (`CI=true`) pushes to the cache by default — devs pull-only — so a
poisoned local environment can't corrupt the shared cache.

Provisioning of the actual backend (Develocity / Buildkite / S3-fronted
Bazel remote cache spec) is a Phase 5 item.

## Frontend (titan-ui)

`pnpm` already gives content-addressed package caching by default; the
`vite build` output is small enough that no Vite-side cache is configured.
The TanStack Router plugin regenerates `routeTree.gen.ts` from `src/routes/`
on every build; that's intentional — the file is committed so type-checks
work on fresh clones without running Vite first.

## Profiling a slow build

```bash
./gradlew test --scan       # publishes a build scan with task-level timings
./gradlew test --info       # in-line task-by-task timings
./gradlew test --dry-run    # task graph only, no execution
```

If a single task dominates, look at:

- Test parallelism — `tasks.test { maxParallelForks = N }` is per-module.
- Spotless — `./gradlew spotlessApply` is incremental; `spotlessCheck` re-reads
  every file but is fast.
- JaCoCo report generation — disable on the per-module basis if you don't
  need coverage for that test run.
