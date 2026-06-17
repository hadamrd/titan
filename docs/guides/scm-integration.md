# SCM integration

How to connect a source-control host so Titan discovers pipelines, triggers
builds on push, and reports status back.

Titan supports **GitHub**, **GitLab**, and **Bitbucket Cloud**. GitHub has a
one-click "golden path" via a GitHub App; GitLab and Bitbucket use a per-trigger
webhook credential. For all three, a build is triggered by an inbound webhook,
and the build's verdict is reported back to the commit.

## GitHub App golden path

This is the recommended way to connect GitHub. The App's id, private key, and
webhook secret are created through GitHub's App-manifest flow and stored
**envelope-encrypted in Titan's database** — there are no environment variables
to manage for them.

As an admin:

1. Open the GitHub integration page in the Titan UI. Titan presents GitHub's
   **App-manifest** flow with the webhook URL pre-filled
   (`https://<your-titan-host>/api/v1/github-app/events`).
2. Approve the manifest on GitHub. GitHub redirects back to
   `POST /api/v1/github-app/manifest-callback`, and Titan exchanges the
   temporary code for the App's id, private key, and webhook secret, persisting
   them as a singleton row.
3. **Install** the App on the org or repos you want Titan to build.
4. Titan scans each installation for pipeline files (`.titan/pipelines/*.yml`)
   and registers the discovered pipelines. You can re-sync an installation with
   `POST /api/v1/github-app/installations/{id}/sync`.
5. Push a commit. The push fires the webhook, Titan enqueues a build, and the
   commit status updates.

The only plain configuration property is `titan.github.api-base-url` (default
`https://api.github.com`), for GitHub Enterprise.

## Webhooks

Each provider posts to its own endpoint. These receivers are unauthenticated by
OIDC — the signature or shared secret on the request **is** the authentication.

| Provider | Endpoint | Verification |
|---|---|---|
| GitHub App | `POST /api/v1/github-app/events` | HMAC-SHA256 (`X-Hub-Signature-256`) against the App's webhook secret |
| GitHub (per-trigger) | `POST /api/v1/triggers/github` | HMAC-SHA256 against the per-job trigger secret |
| GitLab | `POST /api/v1/triggers/gitlab` | shared-secret token (`X-Gitlab-Token`), constant-time compare |
| Bitbucket Cloud | `POST /api/v1/triggers/bitbucket` | per-trigger secret |

For GitLab and Bitbucket, store the webhook secret as a credential (scopes
`gitlab-webhook` / `bitbucket-webhook`) and point the host's webhook at the
endpoint above.

### Deduplication

A matched delivery verifies its signature, then on a branch/event match enqueues
a build carrying `{ branch, commitSha, actor }` — the metadata every status
reporter reads.

GitHub re-delivers any non-2xx delivery, so the GitHub App receiver keeps a
bounded in-memory cache of recent `X-GitHub-Delivery` ids (1024 entries, 10-minute
window) and fast-skips a delivery it has already processed — a retry never
enqueues a duplicate build. A separate durable reconcile subsystem provides
persistent event de-duplication for the polling path.

> The legacy `POST /api/v1/webhooks/github` endpoint is a deprecated redirect
> shim to `/api/v1/triggers/github` and is scheduled for removal. Point new
> webhooks at the canonical endpoints above.

## Status reporting

When a build changes state, Titan posts the verdict back to the SCM
best-effort: a failed post is logged at WARNING and never fails or rolls back
the build (the status is already committed). Tokens never appear in logs.

All three providers report a **commit/build status** under the context
`ci/titan`:

- **GitHub** — a commit status (`createCommitStatus`), plus Check Runs with
  annotations and a sticky PR comment.
- **GitLab** — `POST /api/v4/projects/{id}/statuses/{sha}`, plus a sticky MR
  note and line-level MR discussions (feature-flagged on by default).
- **Bitbucket Cloud** — `POST .../commit/{sha}/statuses/build` (idempotent
  upsert keyed `ci/titan`), plus a sticky PR comment. Inline review is
  flag-gated off.

State mapping is uniform: queued/running → pending, success → success, and
failed/aborted/cancelled/unstable → failure.

## Feature matrix

| Capability | GitHub | GitLab | Bitbucket Cloud |
|---|:--:|:--:|:--:|
| Inbound webhook trigger | ✅ | ✅ | ✅ |
| Commit / build status | ✅ | ✅ | ✅ |
| Sticky PR/MR summary comment | ✅ | ✅ | ✅ |
| Line-level / inline review | ✅ (Check Run annotations) | ✅ (MR discussions) | 🔶 (off by default) |
| Repo discovery / scan | ✅ | ✅ | — |
| Auth | App manifest (JWT + install token) | PAT (`PRIVATE-TOKEN`) | App-password (Basic) |

## See also

- [Credentials & secrets](credentials.md) — storing webhook secrets and tokens.
- [Extending Titan](extending-titan.md) — add a trigger source or status reporter.
