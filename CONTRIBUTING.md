# Contributing to Titan

Thanks for considering a contribution. Titan is a standalone,
cloud-native CI/CD pipeline execution engine, built in the open. See
[`docs/decisions/`](docs/decisions/) for the Architecture Decision Records
and [`docs/getting-started.md`](docs/getting-started.md) to get oriented.

## Quick orientation

- **Single-Gradle, standalone.** The Gradle reactor
  (`settings.gradle.kts`) builds the core engine modules
  (`titan-step-api`, `titan-trigger-api`, `titan-pipeline-model`,
  `titan-db-core`, `titan-server`, `titan-worker`) plus first-party
  extensions under `titan-extensions/`.
- **The frontend is a real SPA.** `titan-ui/` is a Vite + React +
  TypeScript app (its own pnpm setup), built via `task ui:build` —
  not through the Gradle reactor.
- **State lives in a relational database** (Postgres in production,
  embedded H2 for zero-config dev), schema-versioned with Flyway.
- Repo map: [`docs/repo-layout.md`](docs/repo-layout.md).
- Editing docs? The tree is curated — read
  [`docs/maintaining-docs.md`](docs/maintaining-docs.md) first for what
  earns a place, where each kind of doc lives, and the altitude rule.

## Everything goes through `task`

```bash
task              # list every task
task setup        # one-time environment bootstrap
task verify       # full verify (Gradle check on all product modules)
task gradle:check # unit tests + Spotless + SpotBugs + JaCoCo
task gradle:integrationTest   # Testcontainers ITs (needs Docker)
task dev:titan    # bring up the local rig (postgres + keycloak + server + ui + worker)
task e2e          # Playwright against the running local rig
```

Scoped unit tests: `./gradlew -p <module> test --tests <Class>`.
Java 21+ and Docker (for integration tests / the local rig) are required.

## Branding policy (non-negotiable)

Any reference to a specific organization's internal infrastructure —
internal hostnames, registry URLs, org-specific resource paths, magic
job/env names, or bundled org-specific assets — is **forbidden** in
production code, docs, example YAML, screenshots, and committed test
fixtures. Keep examples generic (`example.com`, placeholder IDs).

## Tests

- Unit tests live in `<module>/src/test/`.
- Integration tests (Testcontainers) live in `<module>/src/integrationTest/`.
- End-to-end Playwright tests live in `e2e/`.
- Hunt the sad path — no tautological tests. PRs that move coverage
  backward need a justification.

## Git hooks

A tracked pre-commit hook refuses commits that leave `.java` files
under `src/main/java/` untracked — without it, a commit can call into a
class that only exists on one machine, and the next checkout fails to
compile. The same clean-checkout guarantee is enforced at PR time by the
`task ci:verify` gate (see
[`docs/operations/runbooks/ci-on-pr.md`](docs/operations/runbooks/ci-on-pr.md)).
Install the hooks once after clone, then re-run whenever the tracked hooks
change.

To bypass in an emergency: `git commit --no-verify`. The same invariant
is enforced by the CI compile gate, so it will still be caught at PR time.

## Pull request workflow

1. Open an issue first for substantive changes, so we can align on
   approach before you sink time into it.
2. Branch from `trunk` (never commit to `trunk` directly). Keep PRs
   focused — one logical change per PR.
3. Write tests.
4. Run `task verify` locally before pushing — it's the same set of
   checks CI runs.
5. Format before pushing: `./gradlew spotlessApply`.
6. Commit messages: short imperative subject, blank line, body
   explaining the *why*; reference the issue where relevant.
7. **No GitHub Actions.** CI is the Titan self-pipeline — do not add
   `.github/workflows/*.yml`.

## Code style

- Java: enforced by **Spotless** (Google Java Format) — run
  `./gradlew spotlessApply` before pushing. SpotBugs runs in `task gradle:check`.
- TypeScript / CSS (`titan-ui/`): enforced by the Vite-driven build;
  React 19 + TanStack + Tailwind + shadcn/ui.

## Reporting bugs and security issues

- Functional bugs: open a GitHub issue.
- Security issues: please report privately to the maintainers — do
  **not** open a public GitHub issue for security vulnerabilities.

## Code of conduct

By participating in this project you agree to abide by the
[Code of Conduct](CODE_OF_CONDUCT.md) — the standard Contributor
Covenant: be kind, be patient, no harassment.

## License

By contributing, you agree your contributions will be licensed under
the [MIT License](LICENSE.md).
