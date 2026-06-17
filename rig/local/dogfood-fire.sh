#!/usr/bin/env bash
# Fire a real build of the `titan-demo` job on the running local rig.
#
# Why exists: the rig's titan-server HTTP trigger (POST /api/v1/jobs/{id}/builds)
# is auth-gated by OIDC, which is great for product surface but a friction for
# a one-shot demo. So we do exactly what the server's JobBuildsApi.triggerBuild
# does — insert a `titan.builds` row (status=QUEUED) + an ORCHESTRATE/BAKE task
# on the `default` queue — via psql against the rig's postgres container. The
# orchestrator picks it up, SYNTHESIZEs, dispatches the `sh:` step, the worker
# claims it (it now subscribes to `default` — docker-compose.yml), runs the
# script, streams logs to titan.logs, build hits SUCCESS.
#
# Idempotent: each invocation creates a NEW build (next build_number). Re-fire
# safely.
#
# Usage:  bash rig/local/dogfood-fire.sh  (or `task dogfood:fire`)

set -euo pipefail

cd "$(dirname "$0")"

POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-titan-dev-only}"

# Rig sanity — fail loud if the rig is not up.
if ! curl -fsS -o /dev/null http://localhost:18080/q/health/ready; then
  echo "✗ titan-server not reachable on http://localhost:18080 — run 'task dev:titan' first" >&2
  exit 1
fi

# Resolve the titan-demo job id. If the demo job is missing the user has not
# run the seed yet — bail with a clear message instead of an obscure SQL error.
DEMO_ID=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -At -c \
  "SELECT id FROM titan.jobs WHERE full_name='titan-demo'" 2>/dev/null || echo "")

if [ -z "${DEMO_ID:-}" ]; then
  echo "✗ titan-demo job not found in titan.jobs — run 'bash rig/local/seed-data.sh' first" >&2
  exit 1
fi

echo "▸ firing a real build of titan-demo (job id=${DEMO_ID}) ..."

# Insert build + ORCHESTRATE/START_PIPELINE task atomically. Mirrors
# JobBuildsApi.triggerBuild:
#   - build row, status='QUEUED', triggered_by='dogfood', trigger_type='manual'
#   - ORCHESTRATE/START_PIPELINE task on the 'default' queue, available_at = now
# The build_number is the next sequence value for the job
# (max(build_number)+1, atomic under the implicit row-level lock).
#
# Output parsing — IMPORTANT (loop tick #470 fix):
# `psql -At` is tuples-only BUT still emits transaction-control noise like
# `BEGIN` / `COMMIT` when the input contains explicit `BEGIN;…COMMIT;`. The
# original PR #462 wrapped this in BEGIN/COMMIT and `RETURNING build_id|...`,
# producing output like:
#     BEGIN
#     INSERT 0 1
#     COMMIT
#     <id>|<build_number>
# …so $BUILD_INFO ended up `BEGIN\n…\n<id>|<n>` and `${BUILD_INFO%%|*}` was
# literally the string `BEGIN`, yielding `http://localhost:5180/builds/BEGIN`.
#
# Fix: run as a single statement (no explicit BEGIN/COMMIT — psql wraps a
# single statement implicitly anyway) with a top-level CTE chain. The
# outermost SELECT is the only thing that prints under -At, so the captured
# output is `<build_id>|<build_number>` and nothing else.
BUILD_INFO=$(docker compose exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" \
  postgres psql -U titan -d titan -v ON_ERROR_STOP=1 -v "demo_id=${DEMO_ID}" -At -q <<'SQL'
WITH next_num AS (
  SELECT COALESCE(MAX(build_number), 0) + 1 AS bn
    FROM titan.builds
   WHERE job_id = :demo_id
),
ins_build AS (
  INSERT INTO titan.builds (job_id, build_number, status, triggered_by, trigger_type, queued_at)
  SELECT :demo_id, bn, 'QUEUED', 'dogfood', 'manual', NOW()
    FROM next_num
  RETURNING id, build_number
),
ins_task AS (
  INSERT INTO titan.task_queue
    (type, queue_name, status, priority, payload_json, attempts, max_attempts,
     visibility_timeout_seconds, build_id, available_at)
  SELECT 'ORCHESTRATE', 'default', 'QUEUED', 0,
         '{"action":"SYNTHESIZE","buildId":' || ins_build.id || '}',
         0, 3, 3600, ins_build.id, NOW()
    FROM ins_build
  RETURNING build_id
)
SELECT b.id || '|' || b.build_number FROM ins_build b;
SQL
)

# Strip any stray whitespace; psql -At -q emits only the SELECT row.
BUILD_INFO=$(echo "$BUILD_INFO" | tr -d '[:space:]')

if ! [[ "$BUILD_INFO" =~ ^[0-9]+\|[0-9]+$ ]]; then
  echo "✗ unexpected psql output (expected '<id>|<n>'): '${BUILD_INFO}'" >&2
  exit 1
fi

BUILD_ID="${BUILD_INFO%%|*}"
BUILD_NUMBER="${BUILD_INFO##*|}"

cat <<EOF
  enqueued build #${BUILD_NUMBER} (build_id=${BUILD_ID})
  → orchestrator will SYNTHESIZE → dispatch the sh step to queue 'default'
  → worker (subscribed to 'default') claims and runs:
        echo "Hello from Titan"
        echo "Build id: \$BUILD_ID"
        sleep 2
        uname -a
        date -u
        echo "Done."
  → logs stream to titan.logs; build transitions to SUCCESS in ~5-10s.

Watch it live:
  • UI         : http://localhost:5180/builds/${BUILD_ID}
  • API status : curl -s http://localhost:18080/api/v1/builds/${BUILD_ID} | jq .status
  • Worker log : task dev:logs 2>&1 | grep -E 'worker|claim|step'
EOF
