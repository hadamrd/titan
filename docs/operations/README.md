# Operations

Deploying and running Titan in the real world: how to ship the images,
configure a deployment, observe it, and recover from the failure modes
we've actually hit.

## Sections

| Document | Covers |
|---|---|
| [Deploying](deploying.md) | Docker images for server / UI / worker, the k3s Helm chart, and the required configuration. |
| [Logging](logging.md) | Structured JSON logging from `titan-server`. |
| [Runbooks](runbooks/) | Procedural how-tos for known operational tasks and failure modes. |
| [Post-mortems](post-mortems/) | Retrospectives on real incidents. |

## Operating principles

- **Everything goes through `task`** — the single entry point for build,
  deploy, and rig operations. There is no Makefile or scattered scripts.
- **Secrets never live in git.** Local secrets sit in a gitignored
  `rig/local/.env`; cluster secrets are synced from an external secret
  store into Kubernetes Secrets at deploy time.
- **Fail closed.** A server with no credential KEK provider configured
  refuses to boot rather than silently sealing secrets under a key that
  won't survive a restart — see [runbooks/kek-config.md](runbooks/kek-config.md).
