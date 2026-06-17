/**
 * secret-redaction (#1135) — RETIRED / TOMBSTONE (#1175).
 *
 * This spec was written against the legacy plugin-host harness (`../fixtures` ->
 * `TitanApi`, which POSTs Groovy to `/scriptText` and polls `/job/<name>/...`
 * URLs and relied on `titanApi.seedSecret`). Phase 3 deleted the legacy plugin host
 * (docs/design/58-phase3-deletion-completed.md), so that harness can no longer
 * reach the rig — keeping this "unskipped" would assert against a deleted
 * runtime.
 *
 * The coverage was PORTED — same fixture YAML (e2e/pipelines/secret-redaction/
 * titan-pipeline.yml), no parallel fixture — to the v3 standalone harness:
 *
 *     e2e/specs/golden-path-secret-redaction.spec.ts
 *
 * The v3 port uses the real secret-seed surface (POST /api/v1/credentials),
 * the Keycloak-authed REST API, and the build-detail UI DOM. It is gated
 * `pending` on #1094 (env+secret runtime) with the exact blocking gap named,
 * and runs live under TITAN_SECRET_RUNTIME=1.
 *
 * This file is kept (not deleted) only as a discoverable pointer so a `grep`
 * for "secret-redaction" in tests/ lands on the live location. It is not in the
 * Playwright `testMatch` (only `scenarios.spec.ts` + `specs/**`), so it runs
 * nothing.
 */
import { test } from '@playwright/test'

test.describe('secret-redaction (legacy plugin-host era) — RETIRED, see specs/golden-path-secret-redaction.spec.ts', () => {
  test.skip(
    'ported to e2e/specs/golden-path-secret-redaction.spec.ts (v3 standalone harness) — #1175',
    () => {},
  )
})
