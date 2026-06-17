# Rigs — running Titan locally and on k3s

New here? Everything goes through **`task`** (see `../docs/repo-layout.md`).
You do not need to read the scripts in this directory — `task` calls them.

## Build / compile / test

```sh
task build      # compile + package titan.hpi   (fast — Titan only)
task test       # Titan unit tests
task verify     # full: build + tests + Spotless + coverage
```

The build is self-contained (`./mvnw`) — only a JDK 21 is needed (`task setup`).

## Spin up a rig

### Local — docker-compose (`rig/local/`)
The everyday dev rig. titan-server + Postgres + a Titan worker in containers.

```sh
task deploy:local      # build + (re)deploy; controller at http://localhost:18080
task local:logs        # follow controller + worker logs
task local:down        # stop (keeps the DB volume)
```

### k3s — the public rig (`rig/k3s/`)
The shared cluster rig at `https://titan.test.example.com`.

```sh
task k3s:kubeconfig    # fetch kubeconfig + open the SSH tunnel (do this first)
task deploy:k3s        # build + deploy
task k3s:status        # pod status
task k3s:logs          # tail the controller log
```

## E2E tests (Titan)

The standing end-to-end fixture lives in `../e2e/` — a Playwright project that
drives a rig against the public fixture repo
`github.com/hadamrd/titan-e2e-fixture` (push → webhook → build → gate).

```sh
cd ../e2e && npm install && npx playwright test
```

## What's in here

- `rig/local/` — the docker-compose dev rig.
- `rig/k3s/`  — the k3s rig: `helm/` chart, `k8s/` manifests, `deploy.sh`,
  `fetch-kubeconfig.sh`, secret-setup scripts.

> Some ReleaseFlow-CD-era fixtures (moab e2e, sample CD repos, the GitLab
> values) still sit under `rig/k3s/`. They are being moved out into
> `release-flow-plugin/` — that module will eventually become its own repo.
> For **Titan** work, ignore them and use the `task` commands above.
