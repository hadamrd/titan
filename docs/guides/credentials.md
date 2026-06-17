# Credentials & secrets

How to use credentials in a Titan pipeline, and how Titan keeps them sealed.

Titan stores credentials encrypted in its own database and resolves them on the
controller at dispatch time. The worker never sees the credential store, an id,
or a network path to it — it receives only the resolved values it needs, already
marked for masking.

## Addressing a credential

Every credential is addressed by a `scope/key` tuple. A bare key falls under the
`default` scope:

- `gh-token` → scope `default`, key `gh-token`
- `aws/dev-key` → scope `aws`, key `dev-key`

## Binding a credential into a pipeline

There are two ways to get a secret into a step.

### Structured bindings — `credentials:`

The primary mechanism. A `credentials:` list binds typed credentials to
environment variables for the steps in scope. Four types are supported:

```yaml
stages:
  - stage: Deploy
    steps:
      - sh: ./deploy.sh
        credentials:
          - id: docker-registry            # scope/key (bare key = default scope)
            type: usernamePassword
            usernameVariable: REG_USER
            passwordVariable: REG_PASS
          - id: deploy-token
            type: string
            variable: DEPLOY_TOKEN
          - id: kubeconfig
            type: file
            variable: KUBECONFIG            # var holds the PATH to a temp file
          - id: prod-ssh
            type: sshKey
            keyFileVariable: KEYFILE         # var holds the PATH to the key file
            usernameVariable: SSH_USER       # optional
            passphraseVariable: SSH_PASS     # optional
```

For `file` and `sshKey`, the bound variable holds a **path** to a temporary
file, not the secret content — this keeps the raw value off the command line and
out of the log.

### Env references — `secret:<id>`

A lighter form for plain values: reference a secret directly in an `env:` block.

```yaml
env:
  LOG_LEVEL: info                  # literal, passed through
  GH_TOKEN:  "secret:gh-token"     # default scope
  AWS_KEY:   "secret:aws/dev-key"  # scope/key
```

Malformed references (`secret:`, `secret:a:b`) are rejected at parse time.

## Scoping

`credentials:` can be declared at three levels, narrowest to widest:

- **step** — the binding applies to that one step.
- **stage** — flattened onto every step in the stage at parse time.
- **pipeline** — a root `credentials:` list, applied to every step.

The effective precedence per step is **pipeline > stage > step**; on an
environment-variable-name clash, the wider scope wins. A binding is a
declarative per-step property, not a wrapping block — the persisted pipeline
model carries only ids and binding shape, never secret values.

## The sealing model (envelope encryption)

You do not configure any of this per pipeline, but it is worth understanding:

- Each secret is encrypted with its own random **256-bit data key** using
  AES-256-GCM.
- That data key is then **wrapped** under the operator's **key-encryption key
  (KEK)**. Two layers, two keys — neither half of a stored row is useful alone.
- Each row records the KEK version. **Rotating the KEK** re-wraps only the small
  data keys; it never re-encrypts the payloads.

### Where the KEK comes from

The KEK is supplied by a `CredentialKeyProvider`, selected by an ordered chain.
The shipped options:

| Provider | Source | Use |
|---|---|---|
| Env KEK | `TITAN_KEK` (base64 AES-256) | the production default |
| Infisical | fetched from Infisical | drop-in extension; see config below |
| Dev auto-KEK | auto-generated, persisted | `TITAN_CREDENTIALS_DEV_AUTO_KEK=true` — **dev only** |

The Infisical key provider (`titan-keyprovider-infisical`) reads:
`INFISICAL_TOKEN` (or `INFISICAL_TOKEN_FILE`), `INFISICAL_PROJECT_ID`,
`INFISICAL_ENV` (default `prod`), `INFISICAL_SECRET_PATH` (default `/`),
`INFISICAL_API_URL`, and `INFISICAL_KEY_SECRET_NAME` (default
`TITAN_CREDENTIAL_KEY`). The key is fetched once and cached for the JVM's life,
so KEK rotation through Infisical requires a server restart.

Separately, `TITAN_SECRETS_BACKEND` (default `db-envelope`) selects *where
secrets live*. The default is the envelope-encrypted database backend above; a
HashiCorp Vault backend is also shipped.

## Personal access tokens (PATs)

PATs authenticate API and CLI access. Tokens are prefixed `titanpat_` and minted
via `POST /api/v1/me/tokens` with `name`, `scopes`, and an optional
`jobPattern`. A PAT carries two constraints:

- **scopes** — role narrowing (e.g. `READ_JOB`, `TRIGGER_BUILD`).
- **job pattern** — a glob over a job's full name (`*` = one segment, `**` =
  multi-segment, `?` = one char); empty = unrestricted.

Enforcement is two-gate: the role gate, then a per-request job-scope filter that
resolves the target job and matches the glob. A mismatch returns **403** with a
`application/problem+json` body and writes an audit record (the token is never
logged). Scoping is a no-op for OIDC sessions and for routes with no job in the
path.

## What gets redacted in logs

The worker masks resolved secret values in the build log, replacing each
occurrence with `****`:

- For `usernamePassword`, only the **password** is masked (the username is not
  secret).
- For `file` and `sshKey`, the variable holds a path, so the value is masked
  only on a best-effort basis if the file is small printable text.

Masking is **best-effort, not a security boundary**: it catches `echo $TOKEN`
but cannot catch a secret that a step transforms (base64-encodes, splits, …).
For highly sensitive material, prefer the `file` / `sshKey` types so the raw
value is never a candidate to be printed.

## See also

- [Security](security.md) — the operator's security model.
- [SCM integration](scm-integration.md) — webhook secrets and SCM tokens.
