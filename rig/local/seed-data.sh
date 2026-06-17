#!/usr/bin/env bash
# Seed the local Titan rig with a handful of jobs + historical builds so the
# Overview page has something interesting to render right after `task dev:titan`.
#
# Strategy (closes #507):
#   - JOB CREATION goes through the real public API: POST /api/v1/jobs. This
#     exercises TitanYamlParser.parseAndValidate (the same code path users hit),
#     so an invalid pipeline_script halts the seed instead of being silently
#     persisted (build #46 shipped raw-SQL-bypass invalid PDL — that was the bug).
#     Auth path: ROPC against the dev-only `titan-e2e` Keycloak client (Direct
#     Access Grants enabled in realm-titan-dev.json) with the dev/dev user that
#     carries ADMIN + EDIT_PIPELINE roles. titan-e2e is a public client; no
#     client_secret is needed.
#   - HISTORICAL FIXTURE rows — builds, flow_nodes, task_archive, logs,
#     test_result, artifact — stay raw SQL. There is no public API for these
#     (they are demo fixtures, not user-creatable), and the brief leaves them
#     out of #507 scope.
#
# Idempotent: 409 from POST /api/v1/jobs (duplicate full_name) is treated as a
# benign "already seeded" skip; the historical-fixture INSERTs already carry
# NOT EXISTS guards.

set -euo pipefail

cd "$(dirname "$0")"

POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-titan-dev-only}"
TITAN_API_URL="${TITAN_API_URL:-http://localhost:18080}"
KC_URL="${KC_URL:-http://localhost:8081}"
KC_REALM="${KC_REALM:-titan-dev}"
KC_CLIENT="${KC_CLIENT:-titan-e2e}"
KC_USER="${KC_USER:-dev}"
KC_PASS="${KC_PASS:-dev}"

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "✗ required command '$1' not found on PATH — install it and retry" >&2
    exit 2
  fi
}
require_cmd curl
require_cmd jq

echo "▸ waiting for titan-server /q/health/ready ..."
for i in $(seq 1 60); do
  if curl -fsS -o /dev/null "${TITAN_API_URL}/q/health/ready"; then
    echo "  titan-server ready"
    break
  fi
  sleep 1
  if [ "$i" -eq 60 ]; then
    echo "✗ titan-server did not become healthy in 60s — bailing on seed" >&2
    exit 1
  fi
done

# ── Keycloak ROPC: fetch a bearer for the dev user (ADMIN + EDIT_PIPELINE) ───
# This token is shoved into Authorization: Bearer on every POST /api/v1/jobs
# call. titan.jobs.created_by ends up populated with the OIDC principal name
# (preferred_username='dev'), not the literal 'dev' string the old raw SQL used.
echo "▸ obtaining bearer token from Keycloak (${KC_URL}/realms/${KC_REALM}, client=${KC_CLIENT}) ..."
TOKEN_RESPONSE=$(curl -fsS \
  -X POST "${KC_URL}/realms/${KC_REALM}/protocol/openid-connect/token" \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=password" \
  -d "client_id=${KC_CLIENT}" \
  -d "username=${KC_USER}" \
  -d "password=${KC_PASS}" \
  -d "scope=openid" 2>&1) || {
    echo "✗ Keycloak ROPC failed — is keycloak healthy? Response:" >&2
    echo "$TOKEN_RESPONSE" >&2
    exit 1
  }
BEARER=$(printf '%s' "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
if [ -z "$BEARER" ]; then
  echo "✗ Keycloak response did not contain access_token:" >&2
  echo "$TOKEN_RESPONSE" >&2
  exit 1
fi
echo "  bearer acquired (${#BEARER} chars)"

# ── create_job: POST /api/v1/jobs ────────────────────────────────────────────
# Args: <full_name> <display_name> <pipeline_yaml>
# - 201 → created (echo id)
# - 409 → already exists (idempotent: log + skip, exit 0)
# - 400 → invalid pipeline_script → halt the whole seed with the problem+json
#         body printed so the user sees the parser-located error
# - any other non-2xx → halt
create_job() {
  local full_name="$1"
  local display_name="$2"
  local pipeline_yaml="$3"
  local body
  body=$(jq -nc \
    --arg fullName "$full_name" \
    --arg displayName "$display_name" \
    --arg pipelineScript "$pipeline_yaml" \
    '{fullName:$fullName, displayName:$displayName, pipelineScript:$pipelineScript, enabled:true}')

  local tmp
  tmp=$(mktemp)
  local http_code
  http_code=$(curl -sS -o "$tmp" -w '%{http_code}' \
    -X POST "${TITAN_API_URL}/api/v1/jobs" \
    -H "Authorization: Bearer ${BEARER}" \
    -H 'Content-Type: application/json' \
    --data-binary "$body") || {
      echo "✗ curl failed posting job '${full_name}'" >&2
      rm -f "$tmp"
      exit 1
    }

  case "$http_code" in
    201)
      local id
      id=$(jq -r '.id' < "$tmp")
      echo "  + created job '${full_name}' (id=${id})"
      ;;
    409)
      echo "  = job '${full_name}' already exists — skipping (409)"
      ;;
    400)
      echo "✗ POST /api/v1/jobs rejected '${full_name}' as invalid (HTTP 400):" >&2
      cat "$tmp" >&2
      echo >&2
      rm -f "$tmp"
      exit 1
      ;;
    *)
      echo "✗ POST /api/v1/jobs returned HTTP ${http_code} for '${full_name}':" >&2
      cat "$tmp" >&2
      echo >&2
      rm -f "$tmp"
      exit 1
      ;;
  esac
  rm -f "$tmp"
}

# Are we already seeded? Skip the historical-fixture builds block if there are
# already builds. (Jobs themselves are now created via API and idempotent on
# 409, so we don't need a separate jobs-count guard.)
existing_builds=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT count(*) FROM titan.builds" 2>/dev/null || echo "0")

# Always (re-)post the titan-server + titan-ui jobs via the API. 409 = idempotent.
echo "▸ seeding titan-server + titan-ui jobs via POST /api/v1/jobs ..."
create_job 'titan-server' 'Titan Server' 'stages:
  - stage: build
    steps:
      - sh: echo "building titan-server"
  - stage: test
    steps:
      - sh: echo "running tests"
  - stage: integration-tests
    steps:
      - sh: echo "integration tests"
'
create_job 'titan-ui' 'Titan UI' 'stages:
  - stage: build
    steps:
      - sh: pnpm build
  - stage: deploy-prod
    steps:
      - sh: echo "deploy"
'

if [ "${existing_builds:-0}" -gt 0 ]; then
  echo "▸ titan.builds already populated ($existing_builds rows) — skipping historical builds seed"
else

echo "▸ seeding historical builds (raw SQL — fixtures only, no public API) ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

-- Capture the IDs of the API-seeded jobs (jobs.id is GENERATED ALWAYS AS IDENTITY).
WITH ids AS (
  SELECT
    (SELECT id FROM titan.jobs WHERE full_name='titan-server') AS srv,
    (SELECT id FROM titan.jobs WHERE full_name='titan-ui')     AS ui
)
-- ── historical builds (raw SQL fixtures — honestly tagged) ───────────
-- CONSTITUTION non-negotiable #5: "Real workloads from day 1. No demos
-- that lie." All seeded builds carry triggered_by='seed-fixture' so the
-- UI surfaces them as fixtures, not real user activity. No in-flight
-- (QUEUED/RUNNING) seeds — those would be lies, since no task_queue row
-- backs them and the worker can never claim them. Real in-flight builds
-- come from `task dogfood:fire` or a UI Trigger click.
INSERT INTO titan.builds (job_id, build_number, status, triggered_by, trigger_type,
                          queued_at, started_at, finished_at, duration_ms, error_message)
SELECT srv, 1, 'SUCCESS', 'seed-fixture', 'MANUAL',
       NOW() - INTERVAL '2 hours',  NOW() - INTERVAL '2 hours',  NOW() - INTERVAL '110 minutes', 600000, NULL FROM ids
UNION ALL
SELECT srv, 2, 'SUCCESS', 'seed-fixture', 'MANUAL',
       NOW() - INTERVAL '90 minutes', NOW() - INTERVAL '90 minutes', NOW() - INTERVAL '80 minutes', 600000, NULL FROM ids
UNION ALL
SELECT srv, 3, 'SUCCESS', 'seed-fixture', 'MANUAL',
       NOW() - INTERVAL '40 minutes', NOW() - INTERVAL '40 minutes', NOW() - INTERVAL '30 minutes', 600000, NULL FROM ids
UNION ALL
SELECT srv, 4, 'FAILED', 'seed-fixture', 'MANUAL',
       NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '10 minutes', 600000,
       'integration-tests: connection refused' FROM ids
UNION ALL
SELECT ui, 1, 'SUCCESS', 'seed-fixture', 'MANUAL',
       NOW() - INTERVAL '5 minutes',  NOW() - INTERVAL '5 minutes',
       NOW() - INTERVAL '5 minutes' + INTERVAL '5 seconds', 5000, NULL FROM ids;

COMMIT;
SQL

echo "▸ historical builds seed complete"
SQL_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT (SELECT count(*) FROM titan.jobs) || ' jobs / ' || (SELECT count(*) FROM titan.builds) || ' builds'")
echo "  ${SQL_COUNT}"

fi  # end of historical-builds seed guard

# ── titan-demo job (loop tick #67) ───────────────────────────────────────────
# A REAL runnable pipeline: no pre-seeded build row, no historical fake. Sits
# alongside the historical titan-server/titan-ui seed so the rig still shows
# its usual content on the Overview, but `task dogfood:fire` can enqueue a
# fresh BAKE task against this job and the worker actually executes it end to
# end (echo / sleep / uname / date → streamed to titan.logs → SUCCESS).
#
# Idempotent: ON CONFLICT (full_name) DO NOTHING. Re-running the seed never
# duplicates the job. Builds are NOT seeded — they only appear when a user
# fires the trigger.
echo "▸ ensuring titan-demo job exists ..."
create_job 'titan-demo' 'Titan demo (runnable)' 'stages:
  - stage: hello
    steps:
      - sh: |
          echo "Hello from Titan"
          echo "Build id: ${BUILD_ID}"
          sleep 2
          uname -a
          date -u
          echo "Done."
'
DEMO_ID=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT id FROM titan.jobs WHERE full_name='titan-demo'")
echo "  titan-demo job id: ${DEMO_ID}"

# ── titan.test_result seed (#322) ─────────────────────────────────────────────
# Independently idempotent: only inserts if the target build has no test_result
# rows yet. Targets the second SUCCESS titan-server build (build_number=2) so
# the e2e TestResultsPanel spec has a stable anchor with a known mix:
#   5 PASSED, 2 FAILED, 1 SKIPPED = 8 total.
# Each FAILED row carries a meaningful failure_message; PASSED/SKIPPED leave
# it NULL (matches the V13 schema + the JUnit step writer's contract).
echo "▸ ensuring titan.test_result rows for the e2e anchor build ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

-- Resolve the anchor build id (titan-server build_number=2). If the seed
-- script ran in a prior session and the build exists, this picks it up; if
-- nothing matches, the INSERT below is a no-op (no SELECT rows).
WITH anchor AS (
  SELECT b.id AS build_id
    FROM titan.builds b
    JOIN titan.jobs   j ON j.id = b.job_id
   WHERE j.full_name = 'titan-server' AND b.build_number = 2
), already AS (
  SELECT count(*) AS n
    FROM titan.test_result t, anchor a
   WHERE t.build_id = a.build_id
)
INSERT INTO titan.test_result
  (build_id, node_id, suite, class_name, name, status, duration_ms, failure_message)
SELECT a.build_id, 'step.junit',
       v.suite, v.class_name, v.name, v.status, v.duration_ms, v.failure_message
  FROM anchor a, already al,
       (VALUES
          ('io.adaptiq.titan.LoginTest',     'io.adaptiq.titan.LoginTest',     'authenticates_validUser',          'PASSED',    42::BIGINT, NULL),
          ('io.adaptiq.titan.LoginTest',     'io.adaptiq.titan.LoginTest',     'rejectsExpiredToken',              'PASSED',    37::BIGINT, NULL),
          ('io.adaptiq.titan.BuildTest',     'io.adaptiq.titan.BuildTest',     'enqueuesBuild',                    'PASSED',   118::BIGINT, NULL),
          ('io.adaptiq.titan.BuildTest',     'io.adaptiq.titan.BuildTest',     'cancelsRunningBuild',              'PASSED',   206::BIGINT, NULL),
          ('io.adaptiq.titan.QueueTest',     'io.adaptiq.titan.QueueTest',     'drainsTaskQueue',                  'PASSED',    91::BIGINT, NULL),
          ('io.adaptiq.titan.GateTest',      'io.adaptiq.titan.GateTest',      'approveResumesPipeline',           'FAILED',   312::BIGINT,
           E'java.lang.AssertionError: expected SUCCESS but was RUNNING\n\tat io.adaptiq.titan.GateTest.approveResumesPipeline(GateTest.java:84)\n\tat java.base/java.lang.reflect.Method.invoke(Method.java:580)'),
          ('io.adaptiq.titan.WorkerTest',    'io.adaptiq.titan.WorkerTest',    'heartbeatTimesOutStaleWorker',     'FAILED',  1503::BIGINT,
           E'org.opentest4j.AssertionFailedError: heartbeat watchdog did not evict worker w-7\n\tat io.adaptiq.titan.WorkerTest.heartbeatTimesOutStaleWorker(WorkerTest.java:142)'),
          ('io.adaptiq.titan.ReplayTest',    'io.adaptiq.titan.ReplayTest',    'replayFromGate_pendingFlakyEnv',   'SKIPPED',    0::BIGINT, NULL)
       ) AS v(suite, class_name, name, status, duration_ms, failure_message)
 WHERE al.n = 0;

COMMIT;
SQL

TR_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT count(*) FROM titan.test_result t
     JOIN titan.builds b ON b.id = t.build_id
     JOIN titan.jobs   j ON j.id = b.job_id
    WHERE j.full_name='titan-server' AND b.build_number=2")
echo "  test_result rows on anchor build (titan-server#2): ${TR_COUNT}"

# ── titan.artifact seed (#323) ────────────────────────────────────────────────
# Independently idempotent: only inserts if the target build has no titan.artifact
# rows yet. Targets the SUCCESS titan-server build (build_number=2) so the e2e
# ArtifactsPanel spec has a stable anchor — same build as the test_result seed
# above, so the artifacts and tests tabs both have content on a single build.
#
# Mix of names + sizes so the UI's humanBytes() formatter exercises all three
# branches (B / KB / MB). storage='fs' + a plausible-looking storage_ref are
# pure metadata here — the download endpoint is still a deliberate follow-up
# (see ArtifactsApi javadoc), so the spec asserts the link shape, not bytes.
echo "▸ ensuring titan.artifact rows for the e2e anchor build ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

WITH anchor AS (
  SELECT b.id AS build_id
    FROM titan.builds b
    JOIN titan.jobs   j ON j.id = b.job_id
   WHERE j.full_name = 'titan-server' AND b.build_number = 2
), already AS (
  SELECT count(*) AS n
    FROM titan.artifact t, anchor a
   WHERE t.build_id = a.build_id
)
INSERT INTO titan.artifact
  (build_id, node_id, kind, name, size_bytes, sha256, storage, storage_ref)
SELECT a.build_id, 'step.archiveArtifacts', 'ARTIFACT',
       v.name, v.size_bytes, v.sha256, 'fs', v.storage_ref
  FROM anchor a, already al,
       (VALUES
          ('target/titan-server.jar',
           5242880::BIGINT,
           '0000000000000000000000000000000000000000000000000000000000000001',
           'fs:titan-server/2/ARTIFACT/target/titan-server.jar'),
          ('target/bar.txt',
           1024::BIGINT,
           '0000000000000000000000000000000000000000000000000000000000000002',
           'fs:titan-server/2/ARTIFACT/target/bar.txt'),
          ('target/baz.zip',
           12582912::BIGINT,
           '0000000000000000000000000000000000000000000000000000000000000003',
           'fs:titan-server/2/ARTIFACT/target/baz.zip'),
          ('build/reports/coverage.html',
           204800::BIGINT,
           '0000000000000000000000000000000000000000000000000000000000000004',
           'fs:titan-server/2/ARTIFACT/build/reports/coverage.html')
       ) AS v(name, size_bytes, sha256, storage_ref)
 WHERE al.n = 0;

COMMIT;
SQL

ART_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT count(*) FROM titan.artifact t
     JOIN titan.builds b ON b.id = t.build_id
     JOIN titan.jobs   j ON j.id = b.job_id
    WHERE j.full_name='titan-server' AND b.build_number=2")
echo "  artifact rows on anchor build (titan-server#2): ${ART_COUNT}"

# ── titan.flow_nodes seed for FAILED build #4 (#405) ──────────────────────────
# The SRE 3am long-journey spec (PR #404) needs the FAILED build to carry the
# same shape as the SUCCESS anchor: a per-step DAG so the failure-drill journey
# (Overview → /builds/4 → Pipeline tab → see which step failed → Logs/Tests)
# walks on real data. We seed a 4-row DAG:
#
#   stage-build   STAGE   SUCCESS  (parent of step-compile)
#   step-compile  STEP    SUCCESS
#   step-unit     STEP    SUCCESS
#   step-it       STEP    FAILED   (carries failure_reason — matches build header)
#
# Idempotent: only inserts when the target build has no flow_nodes yet.
# Composite PK is (build_id, node_id); the WHERE NOT EXISTS guards reruns.
echo "▸ ensuring titan.flow_nodes rows for FAILED build (titan-server#4) ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

WITH anchor AS (
  SELECT b.id AS build_id
    FROM titan.builds b
    JOIN titan.jobs   j ON j.id = b.job_id
   WHERE j.full_name = 'titan-server' AND b.build_number = 4
), already AS (
  SELECT count(*) AS n
    FROM titan.flow_nodes f, anchor a
   WHERE f.build_id = a.build_id
)
INSERT INTO titan.flow_nodes
  (build_id, node_id, parent_ids, node_type, display_name, step_descriptor,
   status, started_at, completed_at, duration_ms,
   failure_category, failure_reason)
SELECT a.build_id, v.node_id, v.parent_ids, v.node_type, v.display_name, v.step_descriptor,
       v.status, v.started_at, v.completed_at, v.duration_ms,
       v.failure_category, v.failure_reason
  FROM anchor a, already al,
       (VALUES
          ('stage-build', NULL,           'STAGE', 'build',
           NULL,
           'SUCCESS',
           NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '10 minutes', 600000::BIGINT,
           NULL, NULL),
          ('step-compile', 'stage-build', 'STEP',  'compile',
           'sh',
           'SUCCESS',
           NOW() - INTERVAL '20 minutes', NOW() - INTERVAL '18 minutes', 120000::BIGINT,
           NULL, NULL),
          ('step-unit',    'stage-build', 'STEP',  'unit-tests',
           'sh',
           'SUCCESS',
           NOW() - INTERVAL '18 minutes', NOW() - INTERVAL '15 minutes', 180000::BIGINT,
           NULL, NULL),
          ('step-it',      'stage-build', 'STEP',  'integration-tests',
           'sh',
           'FAILED',
           NOW() - INTERVAL '15 minutes', NOW() - INTERVAL '10 minutes', 300000::BIGINT,
           'STEP_EXIT',
           'integration-tests: connection refused — db-it container did not accept TCP on :5432 within 60s')
       ) AS v(node_id, parent_ids, node_type, display_name, step_descriptor,
              status, started_at, completed_at, duration_ms,
              failure_category, failure_reason)
 WHERE al.n = 0;

COMMIT;
SQL

FN_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT count(*) FROM titan.flow_nodes f
     JOIN titan.builds b ON b.id = f.build_id
     JOIN titan.jobs   j ON j.id = b.job_id
    WHERE j.full_name='titan-server' AND b.build_number=4")
echo "  flow_nodes rows on FAILED build (titan-server#4): ${FN_COUNT}"

# ── titan.test_result seed for FAILED build #4 (#406) ─────────────────────────
# The SRE 3am scenario is failure-driven; test results matter most on the
# failing build. Mirror the SUCCESS anchor's mix (5 PASSED / 2 FAILED / 1 SKIPPED
# = 8 total) and attach to the FAILED step's node_id ('step-it') seeded above.
# FAILED rows carry realistic failure_message text.
#
# Idempotent: only inserts when the target build has no test_result rows yet.
echo "▸ ensuring titan.test_result rows for FAILED build (titan-server#4) ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

WITH anchor AS (
  SELECT b.id AS build_id
    FROM titan.builds b
    JOIN titan.jobs   j ON j.id = b.job_id
   WHERE j.full_name = 'titan-server' AND b.build_number = 4
), already AS (
  SELECT count(*) AS n
    FROM titan.test_result t, anchor a
   WHERE t.build_id = a.build_id
)
INSERT INTO titan.test_result
  (build_id, node_id, suite, class_name, name, status, duration_ms, failure_message)
SELECT a.build_id, 'step-it',
       v.suite, v.class_name, v.name, v.status, v.duration_ms, v.failure_message
  FROM anchor a, already al,
       (VALUES
          ('io.adaptiq.titan.it.SmokeIT',       'io.adaptiq.titan.it.SmokeIT',       'serverBootsHealthy',              'PASSED',    84::BIGINT, NULL),
          ('io.adaptiq.titan.it.SmokeIT',       'io.adaptiq.titan.it.SmokeIT',       'metricsEndpointResponds',         'PASSED',    51::BIGINT, NULL),
          ('io.adaptiq.titan.it.AuthIT',        'io.adaptiq.titan.it.AuthIT',        'oidcRoundTripSucceeds',           'PASSED',   164::BIGINT, NULL),
          ('io.adaptiq.titan.it.JobsIT',        'io.adaptiq.titan.it.JobsIT',        'listsSeededJobs',                 'PASSED',    98::BIGINT, NULL),
          ('io.adaptiq.titan.it.BuildsIT',      'io.adaptiq.titan.it.BuildsIT',      'paginatesRecentBuilds',           'PASSED',   137::BIGINT, NULL),
          ('io.adaptiq.titan.it.DbConnectionIT','io.adaptiq.titan.it.DbConnectionIT','acquiresPooledConnection',        'FAILED',  60012::BIGINT,
           E'java.net.ConnectException: Connection refused (Connection refused)\n\tat java.base/sun.nio.ch.Net.pollConnect(Native Method)\n\tat java.base/sun.nio.ch.SocketChannelImpl.finishConnect(SocketChannelImpl.java:946)\n\tat org.postgresql.core.PGStream.createSocket(PGStream.java:243)\n\tat io.adaptiq.titan.it.DbConnectionIT.acquiresPooledConnection(DbConnectionIT.java:47)'),
          ('io.adaptiq.titan.it.QueueIT',       'io.adaptiq.titan.it.QueueIT',       'drainsTaskQueueViaApi',           'FAILED',   215::BIGINT,
           E'org.opentest4j.AssertionFailedError: expected 200 but was 500\n\tat io.adaptiq.titan.it.QueueIT.drainsTaskQueueViaApi(QueueIT.java:103)'),
          ('io.adaptiq.titan.it.GateIT',        'io.adaptiq.titan.it.GateIT',        'approveResumes_envFlaky',         'SKIPPED',    0::BIGINT, NULL)
       ) AS v(suite, class_name, name, status, duration_ms, failure_message)
 WHERE al.n = 0;

COMMIT;
SQL

TR4_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT count(*) FROM titan.test_result t
     JOIN titan.builds b ON b.id = t.build_id
     JOIN titan.jobs   j ON j.id = b.job_id
    WHERE j.full_name='titan-server' AND b.build_number=4")
echo "  test_result rows on FAILED build (titan-server#4): ${TR4_COUNT}"

# ── titan.artifact seed for FAILED build #4 (#419) ────────────────────────────
# The SRE 3am long-journey spec step 7 (artifacts browser on the failing build)
# currently has to pivot to SUCCESS #2 because the FAILED build carries zero
# artifact rows. Seed a small, failure-context payload set so the failure-drill
# stays on build #4 end-to-end. Mirrors the PR #391 SUCCESS-#2 pattern + the
# PR #413 idempotent-guard pattern.
#
# Sizes span B/KB/MB/large so the UI's humanBytes() formatter exercises all
# branches. Attach to the FAILED step's flow_node ('step-it' per PR #413).
echo "▸ ensuring titan.artifact rows for FAILED build (titan-server#4) ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

WITH anchor AS (
  SELECT b.id AS build_id
    FROM titan.builds b
    JOIN titan.jobs   j ON j.id = b.job_id
   WHERE j.full_name = 'titan-server' AND b.build_number = 4
), already AS (
  SELECT count(*) AS n
    FROM titan.artifact t, anchor a
   WHERE t.build_id = a.build_id
)
INSERT INTO titan.artifact
  (build_id, node_id, kind, name, size_bytes, sha256, storage, storage_ref)
SELECT a.build_id, 'step-it', 'ARTIFACT',
       v.name, v.size_bytes, v.sha256, 'fs', v.storage_ref
  FROM anchor a, already al,
       (VALUES
          ('target/test-reports/junit.xml',
           51200::BIGINT,
           '0000000000000000000000000000000000000000000000000000000000000101',
           'fs:titan-server/4/ARTIFACT/target/test-reports/junit.xml'),
          ('target/heap-dump.hprof',
           262144000::BIGINT,
           '0000000000000000000000000000000000000000000000000000000000000102',
           'fs:titan-server/4/ARTIFACT/target/heap-dump.hprof'),
          ('logs/integration-test.log',
           4194304::BIGINT,
           '0000000000000000000000000000000000000000000000000000000000000103',
           'fs:titan-server/4/ARTIFACT/logs/integration-test.log')
       ) AS v(name, size_bytes, sha256, storage_ref)
 WHERE al.n = 0;

COMMIT;
SQL

ART4_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT count(*) FROM titan.artifact t
     JOIN titan.builds b ON b.id = t.build_id
     JOIN titan.jobs   j ON j.id = b.job_id
    WHERE j.full_name='titan-server' AND b.build_number=4")
echo "  artifact rows on FAILED build (titan-server#4): ${ART4_COUNT}"

# ── titan-hello: a real, runnable, log-bearing SUCCESS build (#???) ───────────
# The other seeded jobs (titan-server / titan-ui) are pure UI fixtures — their
# builds carry no titan.logs rows, so the Build Detail → Logs tab renders empty
# and the CTO bug-bash flagged "no real builds ever ran" (Forge Loop tick #49).
#
# This block lands one calm, descriptive job — titan-hello — with a tiny
# pipeline (one stage, one sh step) AND pre-seeds a completed SUCCESS build #1
# whose flow_node points at a stable task_token UUID; titan.task_archive +
# titan.logs are populated against that token so /api/v1/builds/<id>/logs
# resolves real log lines (the assembler joins task_queue ∪ task_archive →
# titan.logs by task_token; see TaskQueueDao#logTokensForBuild).
#
# Idempotent at every layer: each INSERT is guarded by a NOT EXISTS / row-count
# check, so re-running seed-data.sh is a no-op.
#
# As a hygiene step, we also retire any stale 'default'-labelled QUEUED tasks
# that the live worker (subscribed to TITAN_QUEUE=local-worker-01) cannot
# claim — these would otherwise pile up in the UI Queue page forever.

echo "▸ retiring any stale unclaim-able 'default'-queue tasks ..."
RETIRED=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "UPDATE titan.task_queue
      SET status='CANCELLED', completed_at=CURRENT_TIMESTAMP
    WHERE status='QUEUED'
      AND queue_name='default'
      AND created_at < NOW() - INTERVAL '1 hour'
   RETURNING id" | wc -l)
echo "  stale tasks retired: ${RETIRED}"

echo "▸ ensuring titan-hello job exists (via API) ..."
create_job 'titan-hello' 'Titan Hello' 'stages:
  - stage: hello
    steps:
      - sh: |
          echo "Hello from Titan"
          uname -a
          date -u
'

echo "▸ ensuring titan-hello SUCCESS build #1 + flow_nodes + task_archive + logs (raw SQL fixtures) ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

-- (job row is created above via POST /api/v1/jobs; the rest of this block is
-- fixture-only — builds, flow_nodes, task_archive, logs — no public API.)

-- 2) The completed SUCCESS build. Anchor times to NOW() so it looks fresh
--    in the UI (a 10-second build that finished a minute ago).
INSERT INTO titan.builds
  (job_id, build_number, status, triggered_by, trigger_type,
   queued_at, started_at, finished_at, duration_ms, error_message)
SELECT j.id, 1, 'SUCCESS', 'dev', 'MANUAL',
       NOW() - INTERVAL '70 seconds',
       NOW() - INTERVAL '70 seconds',
       NOW() - INTERVAL '60 seconds',
       10000, NULL
  FROM titan.jobs j
 WHERE j.full_name='titan-hello'
   AND NOT EXISTS (
     SELECT 1 FROM titan.builds b
      WHERE b.job_id = j.id AND b.build_number = 1);

-- 3) The flow-node DAG: 1 STAGE + 1 STEP, both SUCCESS. The STEP carries the
--    log_task_id — a stable UUID we'll also drop into task_archive + logs.
--    Stable UUID literal makes the whole block idempotent.
WITH anchor AS (
  SELECT b.id AS build_id
    FROM titan.builds b
    JOIN titan.jobs   j ON j.id = b.job_id
   WHERE j.full_name = 'titan-hello' AND b.build_number = 1
), already AS (
  SELECT count(*) AS n
    FROM titan.flow_nodes f, anchor a
   WHERE f.build_id = a.build_id
)
INSERT INTO titan.flow_nodes
  (build_id, node_id, parent_ids, node_type, display_name, step_descriptor,
   status, started_at, completed_at, duration_ms, log_task_id)
SELECT a.build_id, v.node_id, v.parent_ids, v.node_type, v.display_name, v.step_descriptor,
       v.status, v.started_at, v.completed_at, v.duration_ms, v.log_task_id
  FROM anchor a, already al,
       (VALUES
          ('stage-hello', NULL,         'STAGE', 'hello', NULL,
           'SUCCESS',
           NOW() - INTERVAL '70 seconds', NOW() - INTERVAL '60 seconds', 10000::BIGINT,
           NULL::uuid),
          ('step-hello',  'stage-hello','STEP',  'echo hello', 'sh',
           'SUCCESS',
           NOW() - INTERVAL '70 seconds', NOW() - INTERVAL '60 seconds', 10000::BIGINT,
           '4e110000-0000-0000-0000-000000003110'::uuid)
       ) AS v(node_id, parent_ids, node_type, display_name, step_descriptor,
              status, started_at, completed_at, duration_ms, log_task_id)
 WHERE al.n = 0;

-- 4) The task_archive row that keys this build's logs. logTokensForBuild()
--    walks task_queue ∪ task_archive by build_id, then joins titan.logs by
--    task_token. We need ONE archive row whose task_token matches the
--    step's log_task_id.
WITH anchor AS (
  SELECT b.id AS build_id
    FROM titan.builds b
    JOIN titan.jobs   j ON j.id = b.job_id
   WHERE j.full_name = 'titan-hello' AND b.build_number = 1
)
INSERT INTO titan.task_archive
  (id, type, queue_name, status, priority, payload_json, result_json,
   attempts, max_attempts, visibility_timeout_seconds,
   claim_token, claimed_by, claimed_at, available_at,
   build_id, node_id, task_token, created_at, completed_at)
SELECT
   (SELECT COALESCE(MAX(id), 0) + 1 FROM titan.task_archive),
   'EXECUTE_COMMAND', 'local-worker-01', 'COMPLETED', 0,
   '{"action":"EXECUTE_STEP","stepDescriptor":"sh"}',
   '{"exitCode":0}',
   1, 3, 3600,
   '4c1a1000-0000-0000-0000-00000000c1a1'::uuid,  -- claim_token (valid hex)
   'local-worker-01',
   NOW() - INTERVAL '70 seconds',
   NOW() - INTERVAL '70 seconds',
   a.build_id, 'step-hello',
   '4e110000-0000-0000-0000-000000003110'::uuid,
   NOW() - INTERVAL '70 seconds',
   NOW() - INTERVAL '60 seconds'
  FROM anchor a
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.task_archive ta
    WHERE ta.task_token = '4e110000-0000-0000-0000-000000003110'::uuid);

-- 5) The real log content. Keyed by task_token UUID. Chunks are ordered by
--    chunk_index; the last carries is_final=true. Stream values must be one of
--    'stdout' | 'stderr' | 'system' (check constraint logs_stream_check).
INSERT INTO titan.logs (task_id, chunk_index, stream, data, is_final)
SELECT '4e110000-0000-0000-0000-000000003110'::uuid, v.chunk_index, v.stream, v.data, v.is_final
  FROM (VALUES
    (0, 'system', '[titan-worker] claimed task on agent=local-worker-01', false),
    (1, 'system', '[titan-worker] starting step: sh',                    false),
    (2, 'stdout', '+ echo "Hello from Titan"',                           false),
    (3, 'stdout', 'Hello from Titan',                                    false),
    (4, 'stdout', '+ uname -a',                                          false),
    (5, 'stdout', 'Linux titan-worker 6.6.87.2 #1 SMP x86_64 GNU/Linux', false),
    (6, 'stdout', '+ date -u',                                           false),
    (7, 'stdout', 'Mon May 23 10:00:02 UTC 2026',                        false),
    (8, 'system', '[titan-worker] step finished: exitCode=0',            true)
  ) AS v(chunk_index, stream, data, is_final)
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.logs
    WHERE task_id = '4e110000-0000-0000-0000-000000003110'::uuid);

COMMIT;
SQL

HELLO_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT
      (SELECT count(*) FROM titan.jobs   WHERE full_name='titan-hello') || ' job / ' ||
      (SELECT count(*) FROM titan.builds b JOIN titan.jobs j ON j.id=b.job_id WHERE j.full_name='titan-hello') || ' builds / ' ||
      (SELECT count(*) FROM titan.flow_nodes f JOIN titan.builds b ON b.id=f.build_id JOIN titan.jobs j ON j.id=b.job_id WHERE j.full_name='titan-hello') || ' flow_nodes / ' ||
      (SELECT count(*) FROM titan.logs WHERE task_id='4e110000-0000-0000-0000-000000003110'::uuid) || ' log chunks'")
echo "  titan-hello seed: ${HELLO_COUNT}"

# ── realistic fleet seed (tick #52) ───────────────────────────────────────────
# PR #427 landed a single titan-hello SUCCESS so the Overview Activity feed
# proves real logs work end-to-end — but one lonely build makes the rig feel
# dead ("Live activity: Idle"). This block lands 3 more jobs + 12 builds spread
# over the last 24h with a realistic status mix (SUCCESS / FAILED / ABORTED /
# RUNNING), each with 5-15 lines of plausible log content, so the Overview /
# Activity / Builds list / Live-activity indicator all look like a real prod
# rig right after `task dev:titan`.
#
# Jobs added: titan-server-ci (4), titan-ui-ci (3), integration-tests (3)
#   plus 2 extra titan-hello builds (1 SUCCESS yesterday + 1 RUNNING now).
# Mix: ~7 SUCCESS / ~3 FAILED / ~1 ABORTED / ~2 RUNNING.
#
# Idempotency: every INSERT is guarded by NOT EXISTS / WHERE al.n=0. The log
# task_token UUIDs are deterministic literals so re-runs hit the same key.
# Schema: identical shape to the PR #427 / #413 titan-hello block above.

echo "▸ seeding fleet jobs (titan-server-ci, titan-ui-ci, integration-tests) via POST /api/v1/jobs ..."
create_job 'titan-server-ci' 'Titan Server CI' 'stages:
  - stage: build
    steps:
      - sh: ./gradlew :titan-server:build
  - stage: test
    steps:
      - sh: ./gradlew :titan-server:test
'

create_job 'titan-ui-ci' 'Titan UI CI' 'stages:
  - stage: install
    steps:
      - sh: pnpm install --frozen-lockfile
  - stage: build
    steps:
      - sh: pnpm build
'

create_job 'integration-tests' 'Integration Tests' 'stages:
  - stage: it
    steps:
      - sh: ./gradlew integrationTest
'

echo "▸ seeding fleet builds + flow_nodes + task_archive (raw SQL fixtures) ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

-- (job rows for titan-server-ci / titan-ui-ci / integration-tests are created
-- above via POST /api/v1/jobs. Everything below is fixture-only.)

-- ── builds: 12 across 4 jobs, spread over last 24h ───────────────────────────
-- Pattern per row: (job_full_name, build_number, status, queued_off_minutes,
-- started_off_minutes, finished_off_minutes_or_null, duration_ms, error_msg).
-- All times are NOW() - INTERVAL '<n> minutes'. Idempotent: NOT EXISTS guard
-- on (job, build_number).
INSERT INTO titan.builds
  (job_id, build_number, status, triggered_by, trigger_type,
   queued_at, started_at, finished_at, duration_ms, error_message)
SELECT j.id, v.build_number, v.status, v.triggered_by, v.trigger_type,
       NOW() - (v.queued_off_min || ' minutes')::interval,
       CASE WHEN v.started_off_min IS NULL THEN NULL
            ELSE NOW() - (v.started_off_min || ' minutes')::interval END,
       CASE WHEN v.finished_off_min IS NULL THEN NULL
            ELSE NOW() - (v.finished_off_min || ' minutes')::interval END,
       v.duration_ms, v.error_message
  FROM (VALUES
    -- titan-server-ci: SUCCESS / SUCCESS / FAILED / SUCCESS
    ('titan-server-ci', 1, 'SUCCESS', 'alice', 'SCM', 1380::int, 1380::int, 1375::int, 300000::bigint, NULL::text),
    ('titan-server-ci', 2, 'SUCCESS', 'bob',   'SCM',  720,       720,       712,       480000,        NULL),
    ('titan-server-ci', 3, 'FAILED',  'bob',   'SCM',  360,       360,       354,       360000,
       E'compile failed: src/main/java/io/adaptiq/titan/Foo.java:42: error: cannot find symbol: variable bar'),
    ('titan-server-ci', 4, 'SUCCESS', 'carol', 'MANUAL', 90,        90,        82,        480000,        NULL),
    -- titan-ui-ci: SUCCESS / RUNNING / SUCCESS
    ('titan-ui-ci', 1, 'SUCCESS', 'alice', 'SCM', 900, 900, 893, 420000, NULL),
    -- tick #70: was 'RUNNING' (no task_queue row → stuck) — flipped to SUCCESS.
    ('titan-ui-ci', 2, 'SUCCESS', 'bob',   'SCM',   3,   3, 2,      60000, NULL),
    ('titan-ui-ci', 3, 'SUCCESS', 'bob',   'SCM', 240, 240, 234, 360000, NULL),
    -- integration-tests: SUCCESS / FAILED (flake) / ABORTED
    ('integration-tests', 1, 'SUCCESS', 'ci-bot', 'CRON', 1080, 1080, 1072, 480000, NULL),
    ('integration-tests', 2, 'FAILED',  'ci-bot', 'CRON',  600,  600,  592, 480000,
       E'port 5432 already in use: container titan-it-pg-2 failed to start (Bind for 0.0.0.0:5432 failed)'),
    ('integration-tests', 3, 'ABORTED','carol',  'MANUAL', 150,  150,  146, 240000,
       'aborted by carol: superseded by newer commit on trunk'),
    -- titan-hello extras: 1 RUNNING + 1 SUCCESS yesterday (build #1 already exists from PR #427)
    -- tick #70: was 'RUNNING' (no task_queue row → stuck) — flipped to SUCCESS.
    ('titan-hello', 2, 'SUCCESS', 'dev',   'MANUAL',  1,    1, 0,      45000, NULL),
    ('titan-hello', 3, 'SUCCESS', 'dev',   'MANUAL', 1320, 1320, 1319, 8000,  NULL)
  ) AS v(job_full_name, build_number, status, triggered_by, trigger_type,
         queued_off_min, started_off_min, finished_off_min, duration_ms, error_message)
  JOIN titan.jobs j ON j.full_name = v.job_full_name
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.builds b
    WHERE b.job_id = j.id AND b.build_number = v.build_number);

-- ── flow_nodes for the 12 new builds ─────────────────────────────────────────
-- Per build: 1 STAGE + 1 STEP. RUNNING builds: stage SUCCESS + step RUNNING
-- (started_at set, completed_at NULL). The STEP's log_task_id is a stable UUID
-- of the form 4e520000-NNNN-0000-0000-000000005200 keyed by build_number+job
-- short-code so we can write logs by literal in the next block.
--
-- Encoding: token UUID literal in v.log_task. Idempotent on (build_id,node_id).
INSERT INTO titan.flow_nodes
  (build_id, node_id, parent_ids, node_type, display_name, step_descriptor,
   status, started_at, completed_at, duration_ms, log_task_id,
   failure_category, failure_reason)
SELECT b.id, v.node_id, v.parent_ids, v.node_type, v.display_name, v.step_descriptor,
       v.status,
       CASE WHEN v.started_off_min IS NULL THEN NULL
            ELSE NOW() - (v.started_off_min || ' minutes')::interval END,
       CASE WHEN v.completed_off_min IS NULL THEN NULL
            ELSE NOW() - (v.completed_off_min || ' minutes')::interval END,
       v.duration_ms, v.log_task::uuid,
       v.failure_category, v.failure_reason
  FROM (VALUES
    -- (job_full_name, build_number, node_id, parent_ids, node_type, display_name, step_descriptor,
    --  status, started_off_min, completed_off_min, duration_ms, log_task, failure_cat, failure_reason)
    -- titan-server-ci#1 SUCCESS
    ('titan-server-ci', 1, 'stage-build', NULL,         'STAGE','build','gradle-build','SUCCESS', 1380::int,1375::int,300000::bigint,'4e520000-0001-0000-0000-000000005201',NULL::text,NULL::text),
    ('titan-server-ci', 1, 'step-build',  'stage-build','STEP', 'gradle',    'sh',        'SUCCESS', 1380,1375,300000,'4e520000-0001-0000-0000-000000005201',NULL,NULL),
    -- titan-server-ci#2 SUCCESS
    ('titan-server-ci', 2, 'stage-build', NULL,         'STAGE','build','gradle-build','SUCCESS', 720,712,480000,'4e520000-0002-0000-0000-000000005202',NULL,NULL),
    ('titan-server-ci', 2, 'step-build',  'stage-build','STEP', 'gradle',    'sh',        'SUCCESS', 720,712,480000,'4e520000-0002-0000-0000-000000005202',NULL,NULL),
    -- titan-server-ci#3 FAILED (compilation)
    ('titan-server-ci', 3, 'stage-build', NULL,         'STAGE','build','gradle-build','FAILED', 360,354,360000,'4e520000-0003-0000-0000-000000005203','STEP_EXIT','javac: cannot find symbol'),
    ('titan-server-ci', 3, 'step-build',  'stage-build','STEP', 'gradle',    'sh',        'FAILED', 360,354,360000,'4e520000-0003-0000-0000-000000005203','STEP_EXIT','javac: cannot find symbol — see logs'),
    -- titan-server-ci#4 SUCCESS
    ('titan-server-ci', 4, 'stage-build', NULL,         'STAGE','build','gradle-build','SUCCESS', 90,82,480000,'4e520000-0004-0000-0000-000000005204',NULL,NULL),
    ('titan-server-ci', 4, 'step-build',  'stage-build','STEP', 'gradle',    'sh',        'SUCCESS', 90,82,480000,'4e520000-0004-0000-0000-000000005204',NULL,NULL),
    -- titan-ui-ci#1 SUCCESS
    ('titan-ui-ci', 1, 'stage-build', NULL,         'STAGE','build','pnpm-build','SUCCESS', 900,893,420000,'4e520000-0005-0000-0000-000000005205',NULL,NULL),
    ('titan-ui-ci', 1, 'step-build',  'stage-build','STEP', 'pnpm',      'sh',         'SUCCESS', 900,893,420000,'4e520000-0005-0000-0000-000000005205',NULL,NULL),
    -- titan-ui-ci#2 SUCCESS — tick #70: was RUNNING, now SUCCESS (build above also flipped).
    ('titan-ui-ci', 2, 'stage-install', NULL,            'STAGE','install','pnpm-install','SUCCESS', 3,2,60000,'4e520000-0006-0000-0000-000000005206',NULL,NULL),
    ('titan-ui-ci', 2, 'step-build',    'stage-install', 'STEP', 'pnpm-build', 'sh',         'SUCCESS', 2,2,60000,'4e520000-0006-0000-0000-000000005206',NULL,NULL),
    -- titan-ui-ci#3 SUCCESS
    ('titan-ui-ci', 3, 'stage-build', NULL,         'STAGE','build','pnpm-build','SUCCESS', 240,234,360000,'4e520000-0007-0000-0000-000000005207',NULL,NULL),
    ('titan-ui-ci', 3, 'step-build',  'stage-build','STEP', 'pnpm',      'sh',         'SUCCESS', 240,234,360000,'4e520000-0007-0000-0000-000000005207',NULL,NULL),
    -- integration-tests#1 SUCCESS
    ('integration-tests', 1, 'stage-it', NULL,      'STAGE','it','gradle-it','SUCCESS', 1080,1072,480000,'4e520000-0008-0000-0000-000000005208',NULL,NULL),
    ('integration-tests', 1, 'step-it',  'stage-it','STEP', 'gradle', 'sh',         'SUCCESS', 1080,1072,480000,'4e520000-0008-0000-0000-000000005208',NULL,NULL),
    -- integration-tests#2 FAILED (port collision)
    ('integration-tests', 2, 'stage-it', NULL,      'STAGE','it','gradle-it','FAILED', 600,592,480000,'4e520000-0009-0000-0000-000000005209','INFRA','port 5432 collision'),
    ('integration-tests', 2, 'step-it',  'stage-it','STEP', 'gradle', 'sh',         'FAILED', 600,592,480000,'4e520000-0009-0000-0000-000000005209','INFRA','testcontainers: port 5432 already bound'),
    -- integration-tests#3 ABORTED
    ('integration-tests', 3, 'stage-it', NULL,      'STAGE','it','gradle-it','ABORTED', 150,146,240000,'4e520000-000a-0000-0000-00000000520a',NULL,'superseded by carol'),
    ('integration-tests', 3, 'step-it',  'stage-it','STEP', 'gradle', 'sh',         'ABORTED', 150,146,240000,'4e520000-000a-0000-0000-00000000520a',NULL,'superseded — manual abort'),
    -- titan-hello#2 SUCCESS — tick #70: was RUNNING, now SUCCESS (build above also flipped).
    ('titan-hello', 2, 'stage-hello', NULL,          'STAGE','hello','echo','SUCCESS', 1::int,0::int,45000::bigint,'4e520000-000b-0000-0000-00000000520b',NULL,NULL),
    ('titan-hello', 2, 'step-hello',  'stage-hello','STEP', 'echo hello','sh','SUCCESS', 1,0,45000,'4e520000-000b-0000-0000-00000000520b',NULL,NULL),
    -- titan-hello#3 SUCCESS yesterday
    ('titan-hello', 3, 'stage-hello', NULL,          'STAGE','hello','echo','SUCCESS', 1320,1319,8000,'4e520000-000c-0000-0000-00000000520c',NULL,NULL),
    ('titan-hello', 3, 'step-hello',  'stage-hello','STEP', 'echo hello','sh','SUCCESS', 1320,1319,8000,'4e520000-000c-0000-0000-00000000520c',NULL,NULL)
  ) AS v(job_full_name, build_number, node_id, parent_ids, node_type, display_name, step_descriptor,
         status, started_off_min, completed_off_min, duration_ms, log_task, failure_category, failure_reason)
  JOIN titan.jobs   j ON j.full_name   = v.job_full_name
  JOIN titan.builds b ON b.job_id = j.id AND b.build_number = v.build_number
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.flow_nodes f
    WHERE f.build_id = b.id AND f.node_id = v.node_id);

-- ── task_archive: one row per terminal build's STEP node so logTokensForBuild
--    resolves the task_token → titan.logs. RUNNING builds skip this (their
--    task would live in task_queue with status PROCESSING — but since we are
--    not driving a real worker here, we ALSO seed those into task_archive
--    with status='PROCESSING' so the Build Detail logs endpoint still works.
--    The (id) PK is taken from MAX(id)+offset to avoid collisions.
INSERT INTO titan.task_archive
  (id, type, queue_name, status, priority, payload_json, result_json,
   attempts, max_attempts, visibility_timeout_seconds,
   claim_token, claimed_by, claimed_at, available_at,
   build_id, node_id, task_token, created_at, completed_at)
SELECT
   (SELECT COALESCE(MAX(id), 0) FROM titan.task_archive) + v.row_off,
   'EXECUTE_COMMAND', 'local-worker-01',
   CASE WHEN v.is_running THEN 'PROCESSING'
        WHEN v.task_status IS NOT NULL THEN v.task_status
        ELSE 'COMPLETED' END,
   0,
   '{"action":"EXECUTE_STEP","stepDescriptor":"sh"}',
   CASE WHEN v.is_running THEN NULL ELSE '{"exitCode":' || v.exit_code || '}' END,
   1, 3, 3600,
   ('4c1a' || lpad(v.row_off::text, 4, '0') || '-0000-0000-0000-00000000c1a1')::uuid,
   'local-worker-01',
   NOW() - (v.started_off_min || ' minutes')::interval,
   NOW() - (v.started_off_min || ' minutes')::interval,
   b.id, v.node_id, v.task_token::uuid,
   NOW() - (v.started_off_min || ' minutes')::interval,
   CASE WHEN v.is_running THEN NULL
        ELSE NOW() - (v.completed_off_min || ' minutes')::interval END
  FROM (VALUES
    (1::int, 'titan-server-ci', 1, 'step-build', '4e520000-0001-0000-0000-000000005201', 1380::int, 1375::int, 0::int, false, 'COMPLETED'::text),
    (2, 'titan-server-ci', 2, 'step-build', '4e520000-0002-0000-0000-000000005202', 720, 712, 0, false, 'COMPLETED'),
    (3, 'titan-server-ci', 3, 'step-build', '4e520000-0003-0000-0000-000000005203', 360, 354, 1, false, 'FAILED'),
    (4, 'titan-server-ci', 4, 'step-build', '4e520000-0004-0000-0000-000000005204',  90,  82, 0, false, 'COMPLETED'),
    (5, 'titan-ui-ci',     1, 'step-build', '4e520000-0005-0000-0000-000000005205', 900, 893, 0, false, 'COMPLETED'),
    -- tick #70: was is_running=true → PROCESSING. Build flipped to SUCCESS, so task COMPLETED too.
    (6, 'titan-ui-ci',     2, 'step-build', '4e520000-0006-0000-0000-000000005206',   2,   1, 0, false, 'COMPLETED'),
    (7, 'titan-ui-ci',     3, 'step-build', '4e520000-0007-0000-0000-000000005207', 240, 234, 0, false, 'COMPLETED'),
    (8, 'integration-tests', 1, 'step-it',  '4e520000-0008-0000-0000-000000005208', 1080,1072,0, false, 'COMPLETED'),
    (9, 'integration-tests', 2, 'step-it',  '4e520000-0009-0000-0000-000000005209', 600, 592, 1, false, 'FAILED'),
    (10,'integration-tests', 3, 'step-it',  '4e520000-000a-0000-0000-00000000520a', 150, 146, 0, false, 'CANCELLED'),
    -- tick #70: was is_running=true → PROCESSING. Build flipped to SUCCESS, so task COMPLETED too.
    (11,'titan-hello',       2, 'step-hello','4e520000-000b-0000-0000-00000000520b',  1,   0, 0, false, 'COMPLETED'),
    (12,'titan-hello',       3, 'step-hello','4e520000-000c-0000-0000-00000000520c',1320,1319,0, false, 'COMPLETED')
  ) AS v(row_off, job_full_name, build_number, node_id, task_token,
         started_off_min, completed_off_min, exit_code, is_running, task_status)
  JOIN titan.jobs   j ON j.full_name = v.job_full_name
  JOIN titan.builds b ON b.job_id = j.id AND b.build_number = v.build_number
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.task_archive ta WHERE ta.task_token = v.task_token::uuid);

COMMIT;
SQL

# Now seed log chunks per build. Doing this in a separate SQL block keeps each
# insert small and readable. Each log set is 5-12 lines, plausible content, and
# guarded by NOT EXISTS on the task_token.
echo "▸ seeding log chunks for fleet builds ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

-- All log chunks in one VALUES table keyed by task_token. Streams: stdout/stderr/system.
-- Idempotent: row-level NOT EXISTS on (task_id, chunk_index) so partial reruns top up.
-- Token tail (last 4 hex): 5201..5204 = titan-server-ci #1..#4;
--   5205..5207 = titan-ui-ci #1..#3 (#2 is RUNNING);
--   5208..520a = integration-tests #1..#3 (#2 FAILED, #3 ABORTED);
--   520b,520c  = titan-hello #2 (RUNNING) and #3 (SUCCESS yesterday).
INSERT INTO titan.logs (task_id, chunk_index, stream, data, is_final)
SELECT v.task_id::uuid, v.idx, v.stream, v.data, v.is_final
  FROM (VALUES
    -- titan-server-ci#1 SUCCESS (9 lines, exitCode=0)
    ('4e520000-0001-0000-0000-000000005201',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0001-0000-0000-000000005201',1,'stdout','+ ./gradlew :titan-server:build',false),
    ('4e520000-0001-0000-0000-000000005201',2,'stdout','> Task :titan-server:compileJava',false),
    ('4e520000-0001-0000-0000-000000005201',3,'stdout','> Task :titan-server:processResources',false),
    ('4e520000-0001-0000-0000-000000005201',4,'stdout','> Task :titan-server:classes',false),
    ('4e520000-0001-0000-0000-000000005201',5,'stdout','> Task :titan-server:jar',false),
    ('4e520000-0001-0000-0000-000000005201',6,'stdout','BUILD SUCCESSFUL in 4m 58s',false),
    ('4e520000-0001-0000-0000-000000005201',7,'stdout','12 actionable tasks: 12 executed',false),
    ('4e520000-0001-0000-0000-000000005201',8,'system','[titan-worker] step finished: exitCode=0',true),
    -- titan-server-ci#2 SUCCESS (8 lines)
    ('4e520000-0002-0000-0000-000000005202',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0002-0000-0000-000000005202',1,'stdout','+ ./gradlew :titan-server:build',false),
    ('4e520000-0002-0000-0000-000000005202',2,'stdout','Reusing configuration cache.',false),
    ('4e520000-0002-0000-0000-000000005202',3,'stdout','> Task :titan-server:compileJava UP-TO-DATE',false),
    ('4e520000-0002-0000-0000-000000005202',4,'stdout','> Task :titan-server:test',false),
    ('4e520000-0002-0000-0000-000000005202',5,'stdout','Tests: 184 passed, 0 failed, 2 skipped',false),
    ('4e520000-0002-0000-0000-000000005202',6,'stdout','BUILD SUCCESSFUL in 7m 52s',false),
    ('4e520000-0002-0000-0000-000000005202',7,'system','[titan-worker] step finished: exitCode=0',true),
    -- titan-server-ci#3 FAILED (compilation error, 11 lines)
    ('4e520000-0003-0000-0000-000000005203',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0003-0000-0000-000000005203',1,'stdout','+ ./gradlew :titan-server:build',false),
    ('4e520000-0003-0000-0000-000000005203',2,'stdout','> Task :titan-server:compileJava FAILED',false),
    ('4e520000-0003-0000-0000-000000005203',3,'stderr','src/main/java/io/adaptiq/titan/Foo.java:42: error: cannot find symbol',false),
    ('4e520000-0003-0000-0000-000000005203',4,'stderr','    int x = bar + 1;',false),
    ('4e520000-0003-0000-0000-000000005203',5,'stderr','            ^',false),
    ('4e520000-0003-0000-0000-000000005203',6,'stderr','  symbol:   variable bar',false),
    ('4e520000-0003-0000-0000-000000005203',7,'stderr','  location: class Foo',false),
    ('4e520000-0003-0000-0000-000000005203',8,'stderr','1 error',false),
    ('4e520000-0003-0000-0000-000000005203',9,'stdout','BUILD FAILED in 5m 54s',false),
    ('4e520000-0003-0000-0000-000000005203',10,'system','[titan-worker] step finished: exitCode=1',true),
    -- titan-server-ci#4 SUCCESS (7 lines)
    ('4e520000-0004-0000-0000-000000005204',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0004-0000-0000-000000005204',1,'stdout','+ ./gradlew :titan-server:build --rerun-tasks',false),
    ('4e520000-0004-0000-0000-000000005204',2,'stdout','> Task :titan-server:compileJava',false),
    ('4e520000-0004-0000-0000-000000005204',3,'stdout','> Task :titan-server:test',false),
    ('4e520000-0004-0000-0000-000000005204',4,'stdout','Tests: 185 passed, 0 failed',false),
    ('4e520000-0004-0000-0000-000000005204',5,'stdout','BUILD SUCCESSFUL in 7m 58s',false),
    ('4e520000-0004-0000-0000-000000005204',6,'system','[titan-worker] step finished: exitCode=0',true),
    -- titan-ui-ci#1 SUCCESS (11 lines)
    ('4e520000-0005-0000-0000-000000005205',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0005-0000-0000-000000005205',1,'stdout','+ pnpm install --frozen-lockfile',false),
    ('4e520000-0005-0000-0000-000000005205',2,'stdout','Lockfile is up to date, resolution step is skipped',false),
    ('4e520000-0005-0000-0000-000000005205',3,'stdout','Progress: resolved 612, reused 612, downloaded 0, added 612',false),
    ('4e520000-0005-0000-0000-000000005205',4,'stdout','+ pnpm build',false),
    ('4e520000-0005-0000-0000-000000005205',5,'stdout','vite v5.4.6 building for production...',false),
    ('4e520000-0005-0000-0000-000000005205',6,'stdout','dist/index.html                   0.46 kB',false),
    ('4e520000-0005-0000-0000-000000005205',7,'stdout','dist/assets/index-CkX2.css       38.21 kB',false),
    ('4e520000-0005-0000-0000-000000005205',8,'stdout','dist/assets/index-D3yM.js       412.88 kB',false),
    ('4e520000-0005-0000-0000-000000005205',9,'stdout','built in 6.94s',false),
    ('4e520000-0005-0000-0000-000000005205',10,'system','[titan-worker] step finished: exitCode=0',true),
    -- titan-ui-ci#2 RUNNING — partial (7 lines, no is_final)
    ('4e520000-0006-0000-0000-000000005206',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0006-0000-0000-000000005206',1,'stdout','+ pnpm install --frozen-lockfile',false),
    ('4e520000-0006-0000-0000-000000005206',2,'stdout','Lockfile is up to date, resolution step is skipped',false),
    ('4e520000-0006-0000-0000-000000005206',3,'stdout','Progress: resolved 612, reused 612, downloaded 0, added 612',false),
    ('4e520000-0006-0000-0000-000000005206',4,'stdout','+ pnpm build',false),
    ('4e520000-0006-0000-0000-000000005206',5,'stdout','vite v5.4.6 building for production...',false),
    ('4e520000-0006-0000-0000-000000005206',6,'stdout','transforming (412) node_modules/react-dom/client.js ...',false),
    -- titan-ui-ci#3 SUCCESS (7 lines)
    ('4e520000-0007-0000-0000-000000005207',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0007-0000-0000-000000005207',1,'stdout','+ pnpm install --frozen-lockfile',false),
    ('4e520000-0007-0000-0000-000000005207',2,'stdout','+ pnpm build',false),
    ('4e520000-0007-0000-0000-000000005207',3,'stdout','vite v5.4.6 building for production...',false),
    ('4e520000-0007-0000-0000-000000005207',4,'stdout','built in 7.12s',false),
    ('4e520000-0007-0000-0000-000000005207',5,'stdout','dist size: 413.4 kB (gzip: 132.1 kB)',false),
    ('4e520000-0007-0000-0000-000000005207',6,'system','[titan-worker] step finished: exitCode=0',true),
    -- integration-tests#1 SUCCESS (7 lines)
    ('4e520000-0008-0000-0000-000000005208',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0008-0000-0000-000000005208',1,'stdout','+ ./gradlew integrationTest',false),
    ('4e520000-0008-0000-0000-000000005208',2,'stdout','> Task :titan-server:integrationTest',false),
    ('4e520000-0008-0000-0000-000000005208',3,'stdout','Starting testcontainers: postgres:16, keycloak:24',false),
    ('4e520000-0008-0000-0000-000000005208',4,'stdout','Tests: 47 passed, 0 failed in 7m 41s',false),
    ('4e520000-0008-0000-0000-000000005208',5,'stdout','BUILD SUCCESSFUL in 8m 0s',false),
    ('4e520000-0008-0000-0000-000000005208',6,'system','[titan-worker] step finished: exitCode=0',true),
    -- integration-tests#2 FAILED (port collision, 10 lines)
    ('4e520000-0009-0000-0000-000000005209',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-0009-0000-0000-000000005209',1,'stdout','+ ./gradlew integrationTest',false),
    ('4e520000-0009-0000-0000-000000005209',2,'stdout','> Task :titan-server:integrationTest',false),
    ('4e520000-0009-0000-0000-000000005209',3,'stdout','Starting testcontainers: postgres:16',false),
    ('4e520000-0009-0000-0000-000000005209',4,'stderr','com.github.dockerjava.api.exception.InternalServerErrorException:',false),
    ('4e520000-0009-0000-0000-000000005209',5,'stderr','  driver failed programming external connectivity on endpoint titan-it-pg-2:',false),
    ('4e520000-0009-0000-0000-000000005209',6,'stderr','  Bind for 0.0.0.0:5432 failed: port is already allocated',false),
    ('4e520000-0009-0000-0000-000000005209',7,'stderr','  at org.testcontainers.containers.GenericContainer.tryStart(GenericContainer.java:560)',false),
    ('4e520000-0009-0000-0000-000000005209',8,'stdout','BUILD FAILED in 8m 0s',false),
    ('4e520000-0009-0000-0000-000000005209',9,'system','[titan-worker] step finished: exitCode=1',true),
    -- integration-tests#3 ABORTED (6 lines)
    ('4e520000-000a-0000-0000-00000000520a',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-000a-0000-0000-00000000520a',1,'stdout','+ ./gradlew integrationTest',false),
    ('4e520000-000a-0000-0000-00000000520a',2,'stdout','> Task :titan-server:integrationTest',false),
    ('4e520000-000a-0000-0000-00000000520a',3,'stdout','Tests: 12 passed (so far)',false),
    ('4e520000-000a-0000-0000-00000000520a',4,'system','[titan-server] received cancel from carol — superseded by newer commit on trunk',false),
    ('4e520000-000a-0000-0000-00000000520a',5,'system','[titan-worker] step aborted: exitCode=143 (SIGTERM)',true),
    -- titan-hello#2 RUNNING — partial (5 lines, no is_final)
    ('4e520000-000b-0000-0000-00000000520b',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-000b-0000-0000-00000000520b',1,'system','[titan-worker] starting step: sh',false),
    ('4e520000-000b-0000-0000-00000000520b',2,'stdout','+ echo "Hello from Titan"',false),
    ('4e520000-000b-0000-0000-00000000520b',3,'stdout','Hello from Titan',false),
    ('4e520000-000b-0000-0000-00000000520b',4,'stdout','+ uname -a',false),
    -- titan-hello#3 SUCCESS yesterday (9 lines)
    ('4e520000-000c-0000-0000-00000000520c',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e520000-000c-0000-0000-00000000520c',1,'system','[titan-worker] starting step: sh',false),
    ('4e520000-000c-0000-0000-00000000520c',2,'stdout','+ echo "Hello from Titan"',false),
    ('4e520000-000c-0000-0000-00000000520c',3,'stdout','Hello from Titan',false),
    ('4e520000-000c-0000-0000-00000000520c',4,'stdout','+ uname -a',false),
    ('4e520000-000c-0000-0000-00000000520c',5,'stdout','Linux titan-worker 6.6.87.2 #1 SMP x86_64 GNU/Linux',false),
    ('4e520000-000c-0000-0000-00000000520c',6,'stdout','+ date -u',false),
    ('4e520000-000c-0000-0000-00000000520c',7,'stdout','Fri May 22 04:00:01 UTC 2026',false),
    ('4e520000-000c-0000-0000-00000000520c',8,'system','[titan-worker] step finished: exitCode=0',true)
  ) AS v(task_id, idx, stream, data, is_final)
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.logs l
    WHERE l.task_id = v.task_id::uuid AND l.chunk_index = v.idx);

COMMIT;
SQL

FLEET_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT
      (SELECT count(*) FROM titan.jobs) || ' jobs / ' ||
      (SELECT count(*) FROM titan.builds) || ' builds / ' ||
      (SELECT count(*) FROM titan.builds WHERE status='RUNNING') || ' running / ' ||
      (SELECT count(*) FROM titan.logs) || ' log chunks total'")
echo "  realistic fleet seed: ${FLEET_COUNT}"

# ── retroactive: titan-server builds #1..#4 logs + archive linkage (tick #66) ──
# Forge Loop tick #66 diagnostic: the four oldest titan-server builds (seeded by
# the very first jobs/builds block at the top of this script — predate the
# task_archive linkage pattern that PR #427 introduced) carry NO log rows and
# NO task_archive rows. The Build Detail → Logs tab therefore renders empty for
# them, and the CTO bug-bash repeatedly hits these (they sit on top of the
# Builds list as #1..#4).
#
# Fix: for each of builds 1..4, wire:
#   - flow_nodes (STAGE + STEP) with a stable log_task_id UUID — only inserts
#     missing nodes (build #4 already has its 4-row DAG from PR #405; we just
#     UPDATE its existing 'step-it' / 'step-compile' / 'step-unit' rows to
#     carry log_task_id values where NULL).
#   - one titan.task_archive row per logged STEP, keyed by the same UUID.
#   - 6-9 lines of realistic log content per STEP.
#
# Token scheme (distinct from existing 4e11.. / 4e52.. families):
#   4e110100-0000-0000-0000-0000000031xx  (build_number xx, step index in suffix)
#   build 1 step-build  → ...3101
#   build 1 step-test   → ...3111
#   build 2 step-build  → ...3102
#   build 2 step-test   → ...3112
#   build 3 step-build  → ...3103
#   build 3 step-test   → ...3113
#   build 4 step-compile → ...3104  (existing flow_node, UPDATE log_task_id)
#   build 4 step-unit    → ...3114  (existing flow_node, UPDATE log_task_id)
#   build 4 step-it      → ...3124  (existing flow_node, UPDATE log_task_id)
#
# Idempotent at every layer (NOT EXISTS / WHERE log_task_id IS NULL guards).

echo "▸ wiring logs + archive linkage for legacy titan-server builds #1..#4 ..."
docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

-- 1) flow_nodes for builds #1..#3 (build #4 already has its DAG from PR #405).
--    Pattern: STAGE 'stage-build' + 2 STEPs ('step-build' compiles, 'step-test'
--    runs tests). Each STEP carries a deterministic log_task_id UUID.
INSERT INTO titan.flow_nodes
  (build_id, node_id, parent_ids, node_type, display_name, step_descriptor,
   status, started_at, completed_at, duration_ms, log_task_id)
SELECT b.id, v.node_id, v.parent_ids, v.node_type, v.display_name, v.step_descriptor,
       v.status,
       b.started_at,
       b.finished_at,
       v.duration_ms,
       v.log_task::uuid
  FROM (VALUES
    -- build #1 SUCCESS
    (1::int, 'stage-build', NULL,         'STAGE', 'build', NULL,     'SUCCESS', 600000::bigint, NULL::text),
    (1,     'step-build',  'stage-build', 'STEP',  'compile', 'sh',   'SUCCESS', 240000,        '4e110100-0000-0000-0000-000000003101'),
    (1,     'step-test',   'stage-build', 'STEP',  'test',    'sh',   'SUCCESS', 360000,        '4e110100-0000-0000-0000-000000003111'),
    -- build #2 SUCCESS (anchor for test_result + artifact e2e; logs are additive)
    (2,     'stage-build', NULL,         'STAGE', 'build', NULL,     'SUCCESS', 600000, NULL),
    (2,     'step-build',  'stage-build', 'STEP',  'compile', 'sh',   'SUCCESS', 240000, '4e110100-0000-0000-0000-000000003102'),
    (2,     'step-test',   'stage-build', 'STEP',  'test',    'sh',   'SUCCESS', 360000, '4e110100-0000-0000-0000-000000003112'),
    -- build #3 SUCCESS
    (3,     'stage-build', NULL,         'STAGE', 'build', NULL,     'SUCCESS', 600000, NULL),
    (3,     'step-build',  'stage-build', 'STEP',  'compile', 'sh',   'SUCCESS', 240000, '4e110100-0000-0000-0000-000000003103'),
    (3,     'step-test',   'stage-build', 'STEP',  'test',    'sh',   'SUCCESS', 360000, '4e110100-0000-0000-0000-000000003113')
  ) AS v(build_number, node_id, parent_ids, node_type, display_name, step_descriptor,
         status, duration_ms, log_task)
  JOIN titan.jobs   j ON j.full_name='titan-server'
  JOIN titan.builds b ON b.job_id=j.id AND b.build_number=v.build_number
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.flow_nodes f
    WHERE f.build_id=b.id AND f.node_id=v.node_id);

-- 2) Backfill log_task_id on build #4's existing STEP flow_nodes (PR #405 left
--    these NULL because it predates the log_task_id column / linkage pattern).
UPDATE titan.flow_nodes f
   SET log_task_id = m.tok::uuid
  FROM (VALUES
    ('step-compile', '4e110100-0000-0000-0000-000000003104'),
    ('step-unit',    '4e110100-0000-0000-0000-000000003114'),
    ('step-it',      '4e110100-0000-0000-0000-000000003124')
  ) AS m(node_id, tok)
 WHERE f.build_id = (
        SELECT b.id FROM titan.builds b
          JOIN titan.jobs j ON j.id=b.job_id
         WHERE j.full_name='titan-server' AND b.build_number=4)
   AND f.node_id = m.node_id
   AND f.log_task_id IS NULL;

-- 3) task_archive rows — one per STEP. Status follows the build / step status.
INSERT INTO titan.task_archive
  (id, type, queue_name, status, priority, payload_json, result_json,
   attempts, max_attempts, visibility_timeout_seconds,
   claim_token, claimed_by, claimed_at, available_at,
   build_id, node_id, task_token, created_at, completed_at)
SELECT
   (SELECT COALESCE(MAX(id), 0) FROM titan.task_archive) + v.row_off,
   'EXECUTE_COMMAND', 'local-worker-01',
   v.task_status,
   0,
   '{"action":"EXECUTE_STEP","stepDescriptor":"sh"}',
   '{"exitCode":' || v.exit_code || '}',
   1, 3, 3600,
   ('4c1a' || lpad((1000 + v.row_off)::text, 4, '0') || '-0000-0000-0000-00000000c1a1')::uuid,
   'local-worker-01',
   b.started_at,
   b.started_at,
   b.id, v.node_id, v.task_token::uuid,
   b.started_at,
   b.finished_at
  FROM (VALUES
    -- build #1 SUCCESS
    (1::int, 1::int, 'step-build', '4e110100-0000-0000-0000-000000003101', 0::int, 'COMPLETED'::text),
    (1,      2,      'step-test',  '4e110100-0000-0000-0000-000000003111', 0,      'COMPLETED'),
    -- build #2 SUCCESS
    (2,      3,      'step-build', '4e110100-0000-0000-0000-000000003102', 0,      'COMPLETED'),
    (2,      4,      'step-test',  '4e110100-0000-0000-0000-000000003112', 0,      'COMPLETED'),
    -- build #3 SUCCESS
    (3,      5,      'step-build', '4e110100-0000-0000-0000-000000003103', 0,      'COMPLETED'),
    (3,      6,      'step-test',  '4e110100-0000-0000-0000-000000003113', 0,      'COMPLETED'),
    -- build #4 FAILED at integration-tests
    (4,      7,      'step-compile','4e110100-0000-0000-0000-000000003104', 0,     'COMPLETED'),
    (4,      8,      'step-unit',   '4e110100-0000-0000-0000-000000003114', 0,     'COMPLETED'),
    (4,      9,      'step-it',     '4e110100-0000-0000-0000-000000003124', 1,     'FAILED')
  ) AS v(build_number, row_off, node_id, task_token, exit_code, task_status)
  JOIN titan.jobs   j ON j.full_name='titan-server'
  JOIN titan.builds b ON b.job_id=j.id AND b.build_number=v.build_number
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.task_archive ta
    WHERE ta.task_token = v.task_token::uuid);

-- 4) Log content. Plausible maven/test output for titan-server.
INSERT INTO titan.logs (task_id, chunk_index, stream, data, is_final)
SELECT v.task_id::uuid, v.idx, v.stream, v.data, v.is_final
  FROM (VALUES
    -- build #1 step-build (compile) — SUCCESS, 7 lines
    ('4e110100-0000-0000-0000-000000003101',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003101',1,'stdout','+ ./gradlew :titan-server:compileJava',false),
    ('4e110100-0000-0000-0000-000000003101',2,'stdout','> Task :titan-server:compileJava',false),
    ('4e110100-0000-0000-0000-000000003101',3,'stdout','> Task :titan-server:processResources',false),
    ('4e110100-0000-0000-0000-000000003101',4,'stdout','> Task :titan-server:classes',false),
    ('4e110100-0000-0000-0000-000000003101',5,'stdout','BUILD SUCCESSFUL in 3m 58s',false),
    ('4e110100-0000-0000-0000-000000003101',6,'system','[titan-worker] step finished: exitCode=0',true),
    -- build #1 step-test — SUCCESS, 7 lines
    ('4e110100-0000-0000-0000-000000003111',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003111',1,'stdout','+ ./gradlew :titan-server:test',false),
    ('4e110100-0000-0000-0000-000000003111',2,'stdout','> Task :titan-server:test',false),
    ('4e110100-0000-0000-0000-000000003111',3,'stdout','Tests: 182 passed, 0 failed, 2 skipped',false),
    ('4e110100-0000-0000-0000-000000003111',4,'stdout','BUILD SUCCESSFUL in 5m 58s',false),
    ('4e110100-0000-0000-0000-000000003111',5,'stdout','12 actionable tasks: 12 executed',false),
    ('4e110100-0000-0000-0000-000000003111',6,'system','[titan-worker] step finished: exitCode=0',true),
    -- build #2 step-build — SUCCESS, 7 lines
    ('4e110100-0000-0000-0000-000000003102',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003102',1,'stdout','+ ./gradlew :titan-server:compileJava',false),
    ('4e110100-0000-0000-0000-000000003102',2,'stdout','> Task :titan-server:compileJava UP-TO-DATE',false),
    ('4e110100-0000-0000-0000-000000003102',3,'stdout','> Task :titan-server:processResources',false),
    ('4e110100-0000-0000-0000-000000003102',4,'stdout','> Task :titan-server:classes',false),
    ('4e110100-0000-0000-0000-000000003102',5,'stdout','BUILD SUCCESSFUL in 3m 52s',false),
    ('4e110100-0000-0000-0000-000000003102',6,'system','[titan-worker] step finished: exitCode=0',true),
    -- build #2 step-test — SUCCESS, 8 lines (the e2e anchor build)
    ('4e110100-0000-0000-0000-000000003112',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003112',1,'stdout','+ ./gradlew :titan-server:test',false),
    ('4e110100-0000-0000-0000-000000003112',2,'stdout','> Task :titan-server:test',false),
    ('4e110100-0000-0000-0000-000000003112',3,'stdout','Tests: 5 passed, 2 failed, 1 skipped',false),
    ('4e110100-0000-0000-0000-000000003112',4,'stderr','GateTest > approveResumesPipeline FAILED',false),
    ('4e110100-0000-0000-0000-000000003112',5,'stderr','WorkerTest > heartbeatTimesOutStaleWorker FAILED',false),
    ('4e110100-0000-0000-0000-000000003112',6,'stdout','BUILD SUCCESSFUL in 6m 0s',false),
    ('4e110100-0000-0000-0000-000000003112',7,'system','[titan-worker] step finished: exitCode=0',true),
    -- build #3 step-build — SUCCESS, 6 lines
    ('4e110100-0000-0000-0000-000000003103',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003103',1,'stdout','+ ./gradlew :titan-server:compileJava',false),
    ('4e110100-0000-0000-0000-000000003103',2,'stdout','> Task :titan-server:compileJava',false),
    ('4e110100-0000-0000-0000-000000003103',3,'stdout','> Task :titan-server:classes',false),
    ('4e110100-0000-0000-0000-000000003103',4,'stdout','BUILD SUCCESSFUL in 3m 58s',false),
    ('4e110100-0000-0000-0000-000000003103',5,'system','[titan-worker] step finished: exitCode=0',true),
    -- build #3 step-test — SUCCESS, 7 lines
    ('4e110100-0000-0000-0000-000000003113',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003113',1,'stdout','+ ./gradlew :titan-server:test',false),
    ('4e110100-0000-0000-0000-000000003113',2,'stdout','> Task :titan-server:test',false),
    ('4e110100-0000-0000-0000-000000003113',3,'stdout','Tests: 184 passed, 0 failed',false),
    ('4e110100-0000-0000-0000-000000003113',4,'stdout','BUILD SUCCESSFUL in 5m 58s',false),
    ('4e110100-0000-0000-0000-000000003113',5,'stdout','12 actionable tasks: 12 executed',false),
    ('4e110100-0000-0000-0000-000000003113',6,'system','[titan-worker] step finished: exitCode=0',true),
    -- build #4 step-compile — SUCCESS, 6 lines
    ('4e110100-0000-0000-0000-000000003104',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003104',1,'stdout','+ ./gradlew :titan-server:compileJava',false),
    ('4e110100-0000-0000-0000-000000003104',2,'stdout','> Task :titan-server:compileJava',false),
    ('4e110100-0000-0000-0000-000000003104',3,'stdout','> Task :titan-server:classes',false),
    ('4e110100-0000-0000-0000-000000003104',4,'stdout','BUILD SUCCESSFUL in 2m 0s',false),
    ('4e110100-0000-0000-0000-000000003104',5,'system','[titan-worker] step finished: exitCode=0',true),
    -- build #4 step-unit — SUCCESS, 6 lines
    ('4e110100-0000-0000-0000-000000003114',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003114',1,'stdout','+ ./gradlew :titan-server:test',false),
    ('4e110100-0000-0000-0000-000000003114',2,'stdout','> Task :titan-server:test',false),
    ('4e110100-0000-0000-0000-000000003114',3,'stdout','Tests: 184 passed, 0 failed',false),
    ('4e110100-0000-0000-0000-000000003114',4,'stdout','BUILD SUCCESSFUL in 3m 0s',false),
    ('4e110100-0000-0000-0000-000000003114',5,'system','[titan-worker] step finished: exitCode=0',true),
    -- build #4 step-it — FAILED, 10 lines (matches the FAILED build error_message)
    ('4e110100-0000-0000-0000-000000003124',0,'system','[titan-worker] claimed task on agent=local-worker-01',false),
    ('4e110100-0000-0000-0000-000000003124',1,'stdout','+ ./gradlew :titan-server:integrationTest',false),
    ('4e110100-0000-0000-0000-000000003124',2,'stdout','> Task :titan-server:integrationTest',false),
    ('4e110100-0000-0000-0000-000000003124',3,'stdout','Starting testcontainers: postgres:16',false),
    ('4e110100-0000-0000-0000-000000003124',4,'stderr','java.net.ConnectException: Connection refused (Connection refused)',false),
    ('4e110100-0000-0000-0000-000000003124',5,'stderr','  at org.postgresql.core.PGStream.createSocket(PGStream.java:243)',false),
    ('4e110100-0000-0000-0000-000000003124',6,'stderr','  at io.adaptiq.titan.it.DbConnectionIT.acquiresPooledConnection(DbConnectionIT.java:47)',false),
    ('4e110100-0000-0000-0000-000000003124',7,'stderr','integration-tests: connection refused — db-it container did not accept TCP on :5432 within 60s',false),
    ('4e110100-0000-0000-0000-000000003124',8,'stdout','BUILD FAILED in 5m 0s',false),
    ('4e110100-0000-0000-0000-000000003124',9,'system','[titan-worker] step finished: exitCode=1',true)
  ) AS v(task_id, idx, stream, data, is_final)
 WHERE NOT EXISTS (
   SELECT 1 FROM titan.logs l
    WHERE l.task_id = v.task_id::uuid AND l.chunk_index = v.idx);

COMMIT;
SQL

LEGACY_COUNT=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT
      'builds 1..4 log-archive linkage: ' ||
      (SELECT count(*)
         FROM titan.builds b
         JOIN titan.jobs j ON j.id=b.job_id
         JOIN titan.task_archive ta ON ta.build_id=b.id
         JOIN titan.logs l ON l.task_id=ta.task_token
        WHERE j.full_name='titan-server' AND b.build_number BETWEEN 1 AND 4) || ' log rows joined'")
echo "  ${LEGACY_COUNT}"
