# Repository layout & ops

The single rule: the repo root holds the **Gradle reactor** plus exactly
**three role-named directories** — nothing else loose.

```
titan/
├── Taskfile.yml        THE entry point — build / test / deploy / setup
├── gradlew, gradle/    self-contained Gradle build (no system gradle needed)
├── settings.gradle.kts      Gradle reactor — core product modules + e2e:
│   ├── titan-step-api/         step SPI (jar)
│   ├── titan-trigger-api/      trigger SPI (jar)
│   ├── titan-secrets-api/      secrets-backend SPI (jar)
│   ├── titan-pipeline-model/   pipeline model + parser + schema (jar)
│   ├── titan-db-core/          shared DB substrate (jar)
│   ├── titan-server/           Quarkus API + engine (jar)
│   ├── titan-worker/           standalone pull-based worker (jar)
│   └── titan-extensions/       first-party pluggable extensions:
│       ├── titan-artifact-s3/            S3/R2 artifact backend
│       ├── titan-artifact-nexus/         Nexus artifact backend
│       ├── titan-keyprovider-infisical/  Infisical key provider
│       └── titan-secrets-vault/          Vault secrets backend
│
├── titan-ui/             React/Vite frontend (own pnpm setup — outside the Gradle reactor)
│
├── .titan/             the dogfood pipeline (Titan compiles/tests/deploys Titan):
│   └── pipeline.yml    self-build PDL — exercises every shipped grammar keyword
├── dev/                developer ENVIRONMENT — setup-env.sh, git-hooks/,
│                       rig-smoke/ (shell helpers behind `task rig:smoke`)
├── rig/                deployment TARGETS
│   ├── local/          docker-compose dev rig (postgres + keycloak + server + ui + worker)
│   └── k3s/            the public k3s rig (Helm chart)
├── e2e/                end-to-end tests (Playwright; Gradle-tracked fixture project)
└── docs/               product documentation
```

`dev/` = how you set up *your machine*. `rig/` = where the engine gets
*deployed*. `e2e/` = tests. Build/runtime dirs (`build/`, `.gradle/`,
`node_modules/`) are gitignored and never committed.

## One entry point

Everything goes through `task` ([taskfile.dev](https://taskfile.dev)).
There is no Makefile, no justfile, no scattered scripts.

```sh
task setup           one-time: JDK 21, git hooks
task gradle:build    build every product module
task build:worker    the standalone titan-worker fat jar
task gradle:check    unit tests + Spotless + SpotBugs + JaCoCo
task gradle:integrationTest  Testcontainers ITs (needs Docker)
task dev:titan       bring up the local rig
task dev:down / dev:logs
task deploy:k3s      publish + deploy to the k3s rig
task e2e             Playwright against the running rig
task --list          everything
```

The build runs through `./gradlew` — no system Gradle required; only a
JDK 21 (provisioned by `task setup`).

## CI

CI runs as a Titan pipeline **on the k3s rig** — Titan building Titan, on
infrastructure already paid for. GitHub Actions is not used.
