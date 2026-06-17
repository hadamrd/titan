# Security

The Titan security model from an operator's point of view.

This guide covers the controls an operator relies on: how requests are
authenticated and authorized, how credentials are sealed at rest, what is masked
in logs, and how inbound webhooks are verified.

## Authentication & authorization

- **Interactive access** is authenticated via **OIDC** (Keycloak on the
  reference rig). API routes are gated by role.
- **Programmatic access** uses personal access tokens (prefix `titanpat_`).
  Beyond role scopes, a PAT can be **job-scoped** with a glob over a job's full
  name; a request outside the pattern is rejected with **403** and an audit
  record (the token is never logged). See
  [Credentials & secrets](credentials.md#personal-access-tokens-pats).

## Credentials at rest

Credentials live in Titan's database under **envelope encryption**:

- each secret is encrypted with its own random 256-bit data key (AES-256-GCM);
- that data key is wrapped under an operator-controlled key-encryption key
  (KEK), so neither half of a stored row is useful alone;
- the KEK is supplied by a `CredentialKeyProvider` (env, Infisical, or a
  dev-only auto-key), and **rotating it re-wraps only the data keys** — payloads
  are never re-encrypted.

Resolution happens on the **controller at dispatch**. The worker never sees the
credential store, a credential id, or a path to it — only the resolved values
for the step it is about to run. The full model is in
[Credentials & secrets](credentials.md#the-sealing-model-envelope-encryption).

## Secret redaction in logs

The worker wraps every step's log sink and replaces known secret values with
`****` before the log is streamed, displayed, or persisted:

- for a username/password credential, only the **password** is masked;
- for `file` / `sshKey` credentials the bound variable holds a path, so the raw
  value is never on the command line.

Redaction is **best-effort, not a security boundary**. It catches a secret
printed verbatim (`echo $TOKEN`) but cannot catch one a step transforms
(base64-encodes, splits across lines). Bind highly sensitive material as a
`file` or `sshKey` credential so the value is never a candidate for printing.

## Inbound webhooks

SCM webhook endpoints are not behind OIDC — the request's signature or shared
secret **is** the authentication:

- **GitHub** deliveries are verified by HMAC-SHA256 (`X-Hub-Signature-256`)
  against the App's webhook secret, which is stored envelope-encrypted, unsealed
  per request, and never cached;
- **GitLab** uses a constant-time shared-secret token compare;
- **Bitbucket** uses a per-trigger secret.

Replayed GitHub deliveries are de-duplicated so a retry cannot enqueue a
duplicate build. See [SCM integration](scm-integration.md#webhooks).

## Extension trust boundary

Third-party step extensions dropped into `TITAN_STEPS_DIR` are loaded under
guards: an API-version gate, fail-fast on a duplicate step keyword, and a
**reserved-namespace gate** that refuses any drop-in jar bundling a class under
`io.adaptiq.titan.`. Extension code must live in its own namespace. See
[Extending Titan](extending-titan.md#discovery-safety).

## Reporting a vulnerability

Report security issues privately to the maintainers rather than opening a public
GitHub issue.

## See also

- [Credentials & secrets](credentials.md) — binding and sealing details.
- [SCM integration](scm-integration.md) — webhook verification.
