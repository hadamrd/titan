# Runbooks

Procedural how-tos for known operational tasks and failure modes — each
one written against a real situation we've hit.

| Runbook | When you need it |
|---|---|
| [kek-config.md](kek-config.md) | Configuring the credential-envelope KEK, and the fail-fast behaviour when none is wired up. |
| [observability.md](observability.md) | Wiring Titan's Prometheus metrics and OpenTelemetry traces to your back-end. |
| [github-app-dev.md](github-app-dev.md) | Relaying GitHub webhooks to a local dev rig via a smee.io tunnel. |
| [ci-on-pr.md](ci-on-pr.md) | The `task ci:verify` pre-merge gate and the build-time break classes it catches. |
