# Database Schema

The tables Titan persists, grouped by subsystem. The schema is defined by Flyway migrations in `titan-db-core` and is the engine's single source of durable state — there are no config files on disk.

## Migrations

- **Engine:** Flyway, all DDL under schema `titan`, applied at server startup.
- **Naming:** `V<n>__<description>.sql` (versioned), `V<n>_<m>__<description>.sql` (Postgres-only minor refinements), `R__<description>.sql` (repeatable).
- **Two source trees:**
  - `db/migration/` — portable DDL that runs on both H2 (embedded / test, Postgres-compatibility mode) and PostgreSQL (production).
  - `db/migration-postgresql/` — a Postgres-only overlay for features H2 cannot express. Production and integration tests load both trees; unit/H2 tests load only the portable tree.

The portable baseline runs V1–V44. The Postgres overlay adds partial indexes (claimable-task, deadline, active-PAT, cancel-intent), the `logs` full-text-search `tsvector` column with its GIN index, the `flow_nodes` `SLEEPING` status, and `OWNED BY` sequence ownership.

## Pipelines and builds

### `jobs`
One row per pipeline (configuration). Replaces the per-job config file.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT | PK |
| `full_name` | VARCHAR(512) | unique |
| `display_name` | VARCHAR(255) | |
| `folder_path` | VARCHAR(512) | indexed |
| `pipeline_script` | text | the PDL YAML |
| `config_json` | text | trigger defs, SCM block (default `{}`) |
| `created_by`, `created_at`, `updated_at` | | |
| `enabled` | BOOLEAN | default true, indexed |
| `github_installation_id`, `github_repo_id` | BIGINT | GitHub App linkage, indexed |

### `builds`
One row per build execution.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT | PK |
| `job_id` | BIGINT | FK→jobs, cascade |
| `build_number` | INT | unique per job |
| `status` | VARCHAR(16) | QUEUED/RUNNING/SUCCESS/FAILED/ABORTED/UNSTABLE |
| `parameters_json` | text | params the build ran with |
| `triggered_by`, `trigger_type` | | |
| `deployment_id` | BIGINT | indexed |
| `queued_at`, `started_at`, `finished_at`, `duration_ms` | | |
| `error_message`, `pipeline_model_json`, `started_by_instance` | | |
| `failure_summary` | text | pre-node failure reason |
| `replayed_from_build_id` | BIGINT | FK→builds (replay lineage) |
| `replayed_from_node_id` | VARCHAR(64) | |
| `deadline_at` | TIMESTAMPTZ | pipeline-timeout deadline |
| `trigger_meta_json` | VARCHAR(8192) | branch / commit / actor |
| `display_name` | VARCHAR(200) | from `setBuildName` |
| `external_check_run_id` | BIGINT | GitHub check-run id |
| `failure_cause`, `failure_cause_detail` | text | classified cause + log snippet |

### `flow_nodes`
The per-build execution DAG (stages, steps, parallel branches). Composite PK `(build_id, node_id)`.

| Column | Type | Notes |
|---|---|---|
| `build_id` | BIGINT | PK part, FK→builds cascade |
| `node_id` | VARCHAR(64) | PK part |
| `parent_ids` | VARCHAR(512) | indexed |
| `node_type` | VARCHAR(16) | STAGE/STEP/PARALLEL_BRANCH |
| `display_name`, `step_descriptor`, `step_args_json` | | |
| `status` | VARCHAR(16) | PENDING/QUEUED/RUNNING/SUCCESS/FAILED/ABORTED/SKIPPED (+ SLEEPING on Postgres) |
| `agent_id`, `agent_label` | | |
| `started_at`, `completed_at`, `duration_ms`, `result_json`, `log_task_id` | | |
| `attempt`, `max_attempts` | INT | retry bookkeeping |
| `failure_category`, `failure_reason` | text | STEP_EXIT/CREDENTIAL/DISPATCH/BAKE/SYNTHESIS/PRECONDITION/TIMEOUT/INTERNAL |
| `wake_at` | TIMESTAMP | durable-sleep wake instant |

## Task queue

### `task_queue`
The durable work queue. Workers claim rows with `SELECT … FOR UPDATE SKIP LOCKED`.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT | PK |
| `type` | VARCHAR(32) | START_PIPELINE/EXECUTE_COMMAND/ORCHESTRATE/CANCEL_TASK/EXECUTE/CANCEL |
| `queue_name` | VARCHAR(128) | default `default` |
| `status` | VARCHAR(16) | QUEUED/CLAIMED/PROCESSING/COMPLETED/FAILED/TIMED_OUT/CANCELLED |
| `priority` | INT | default 0 |
| `payload_json`, `result_json` | text | |
| `attempts`, `max_attempts` | INT | default 0 / 3 |
| `visibility_timeout_seconds` | INT | default 3600 |
| `claim_token` | UUID | lease token |
| `claimed_by`, `claimed_at` | | |
| `available_at` | TIMESTAMP | visibility gate |
| `build_id` | BIGINT | FK→builds cascade |
| `node_id` | VARCHAR(64) | |
| `task_token` | UUID | unique |
| `created_at`, `completed_at` | | |
| `trace_parent` | VARCHAR(64) | W3C traceparent |
| `cancel_requested_at` | TIMESTAMPTZ | durable cancel intent |

### `task_archive`
Terminal tasks swept here by the reaper. Mirrors `task_queue`'s columns; `id` is sourced from a dedicated sequence and `task_token` is uniquely constrained.

## Timers and approvals

### `timers`
Durable scheduled wake-ups.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT | PK |
| `build_id`, `node_id` | | target node |
| `kind` | VARCHAR(16) | SLEEP/TIMEOUT/RETRY_BACKOFF/GATE_RESUME |
| `fire_at` | TIMESTAMP | |
| `status` | VARCHAR(16) | ARMED/CLAIMED/FIRED/CANCELLED |
| `payload_json`, `claim_token`, `claimed_at`, `created_at`, `fired_at` | | |

### `approvals`
Human-approval gate state.

| Column | Type | Notes |
|---|---|---|
| `id` | BIGINT | PK |
| `build_id` | BIGINT | FK→builds cascade |
| `flow_node_id` | VARCHAR(255) | |
| `prompt` | VARCHAR(2048) | |
| `approvers_json` | VARCHAR(8192) | default `[]` |
| `status` | VARCHAR(16) | PENDING/APPROVED/REJECTED/TIMED_OUT |
| `decided_by`, `decided_at`, `expires_at`, `created_at` | | |

## Agents

### `agents`
Worker registry, keyed by `agent_id` (natural PK). Tracks `display_name`, `labels`, `status` (ONLINE/OFFLINE/BUSY/DRAINING), `num_executors`, `max_concurrent`, `current_tasks`, `os_info`, `java_version`, `capabilities_json`, `last_heartbeat`, `registered_at`, `endpoint_url`, `remote_fs`, `usage_mode`, and `cpu_percent` / `memory_percent` / `disk_percent`.

### `agent_events`
Worker join/leave lifecycle log — `agent_id` (FK→agents cascade), `event_type` (JOINED/LEFT), `occurred_at`, `meta`.

## Logs

### `logs`
Chunked per-task build log storage. `task_id` (UUID), `chunk_index`, `stream` (stdout/stderr/system), `data`, `produced_at`, `is_final`; unique on `(task_id, chunk_index)`. On Postgres a generated `tsv` tsvector column with a GIN index backs full-text build-log search.

## Artifacts and fingerprints

### `artifact`
Archived artifact / stash metadata (bytes live in the artifact store). Columns: `build_id` (FK cascade), `node_id`, `kind` (ARTIFACT/STASH), `name`, `size_bytes`, `sha256`, `storage`, `storage_ref`; unique on `(build_id, kind, name)`.

### `fingerprint`
Canonical record per content hash — `hash` (PK), `file_name`, `first_build_id`, `first_seen`.

### `fingerprint_ref`
One edge per `(hash, build, role)` where `role` is PRODUCED or USED; unique on `(hash, build_id, role)`.

## Discovery and triggers

### `job_triggers`
Per-trigger mutable runtime state — composite PK `(job_id, trigger_id)`, `last_fired_at`, `last_error`. Trigger *definitions* live in `jobs.config_json`.

### `discovery_sources`
Watched locations and last poll outcome — `name` (unique), `source_type`, `config_json`, `enabled`, `last_polled_at`, `last_status`, `last_error`.

### `discovery_events`
One observed pipeline file at one revision — `source_id` (FK cascade), `repo`, `branch`, `commit_sha`, `event_type`, `status` (pending/processing/done/failed), claim fields; unique on `(source_id, repo, commit_sha)`.

### `discovery_state`
Per-job SCM polling state — `job_id` (PK, FK cascade), `last_seen_sha`, `last_polled_at`, `last_status`, `last_error`.

## SCM webhook ingestion

### `scm_event_cursor`
Per-`(provider, repo)` high-water-mark for reconciliation.

### `scm_event_seen`
Exactly-once dispatch dedupe — composite PK `(provider, event_id)`, `source` (webhook/reconcile).

### `scm_webhook_event`
Durable webhook ingestion log with retry — `provider`, `delivery_id`, `event_type`, `status` (PENDING/PROCESSED/FAILED), `attempts`, `next_attempt_at`, `payload`, `signature`; unique on `(provider, delivery_id)`.

## GitHub App integration

### `github_app`
The single registered GitHub App (singleton row, `id = 1`). Holds the envelope-encrypted PEM and webhook secret.

### `github_installations`
One row per org/user install — `install_id` (unique), `account_login`, `account_type`, `target_type`, `suspended_at`.

### `github_repositories`
Repositories per installation — `install_id` (FK cascade), `repo_id` (unique), `owner`, `name`, `default_branch`, `is_private`, `last_scanned_at`.

### `github_pipelines_discovered`
Discovered pipeline files, branch-aware — `repo_id` (FK cascade), `filename`, `content_sha`, `parsed_metadata`, `parse_error`, `branch`; unique on `(repo_id, branch, filename)`.

## Credentials and secrets

### `credentials`
Envelope-encrypted secret store — `kind` (USERNAME_PASSWORD/SSH_KEY/STRING/FILE), `scope`, `cred_key`, `sealed_value`, `aad`, `wrapped_dek`, `kek_version`, `dek_version`; unique on `(scope, cred_key)`.

### `system_secret`
Durable server-generated secrets (e.g. the artifact-download HMAC key) — `name` (PK), `secret_value`.

## Personal access tokens

### `personal_access_tokens`
Per-user API tokens — `user_subject`, `name`, `token_hash` (BCrypt), `prefix`, `created_at`, `last_used_at`, `revoked_at` (soft delete), `scopes_json` (role-set narrowing), `job_pattern` (glob path restriction); unique on `(user_subject, name)`.

## RBAC and audit

### `user_roles`
Flat global `(user_id, role)` — roles ADMIN/MAINTAINER/DEVELOPER/VIEWER.

### `rbac_user_role`
Scoped role assignments — composite PK `(user_id, scope_kind, scope_id, role)` where `scope_kind` is ORG or REPO. Scoped checks fall back to the global table.

### `group_role_mapping`
SSO group → role per org — `org_id`, `group_path`, `role`; unique on `(org_id, group_path)`.

### `audit_log`
Generic auditable action trail — `occurred_at`, `actor`, `action`, `target_type`, `target_id`, `details_json`.

### `rbac_audit`
Typed RBAC decision trail — `user_id`, `endpoint`, `scope_kind`, `scope_id`, `required_role`, `effective_role`, `decision` (ALLOW/DENY).

### `audit_retention_policy`
Per-kind audit retention — `kind` (PK; an action code or `*` sentinel), `max_age_days`.

## Miscellaneous

### `test_result`
Per-test-case JUnit results — `build_id` (FK cascade), `node_id`, `suite`, `class_name`, `name`, `status` (PASSED/FAILED/SKIPPED), `duration_ms`, `failure_message`. The writer drops and reinserts per build.

### `user_starred_jobs`
Per-user pinned jobs — composite PK `(user_subject, job_id)`, FK→jobs cascade.

### `pulsar_sources`
Registered Pulsar SCM node connections — `node_url` (unique), `node_name`, `repo_count`, `last_polled_at`.
