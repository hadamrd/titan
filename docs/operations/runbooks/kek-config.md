# KEK (credential key) configuration

> **What this covers.** Configuring the credential-envelope KEK on a titan-server
> deployment, and the fail-fast behaviour the server enforces when no KEK is wired up.

## Quick reference

| Situation | What titan-server does | What you should do |
|---|---|---|
| `TITAN_PROFILE=dev` + `LOOP_TITAN_ALLOW_DEV_KEK=1` | Generates a host-local AES-256 KEK at `TITAN_DEV_KEK_PATH`, reuses it across boots. DEV ONLY. | Nothing — this is the `task dev:titan` path. |
| `TITAN_PROFILE=dev` (no allow flag) | Boots; chain is inert; credential writes fail with a clear error at the first attempt. | Set `LOOP_TITAN_ALLOW_DEV_KEK=1` if you actually need credentials in your dev rig. |
| `TITAN_PROFILE` unset / `prod`, KMS provider jar on classpath | Boots normally; KMS provider seals credentials. | This is the prescribed prod shape. |
| `TITAN_PROFILE` unset / `prod`, `TITAN_KEK=<base64>` set | Boots; the env KEK provider (`EnvKeyProvider`) seals credentials. | Acceptable for single-team prod where the env is injected directly. |
| `TITAN_PROFILE` unset / `prod`, no KMS jar, no env | **Refuses to boot** with `ConfigException`. | Configure one of the two prod options above. |
| `TITAN_PROFILE=prod` + `LOOP_TITAN_ALLOW_DEV_KEK=1` (an SRE leaked the dev allow var into prod) | **Refuses to boot** — `DevAutoKeyProvider` constructor throws `ConfigException`. | Unset `LOOP_TITAN_ALLOW_DEV_KEK` in the prod manifest. The profile gate is the safety net here; the allow var alone cannot bypass it. |

## Why titan-server fails fast (issue #1076 / V1-bar #5)

The credential-envelope KEK is the root of trust for every secret a user stores in
titan: PATs, webhook signing secrets, registry credentials. If titan-server can boot
without a KEK provider configured, a window opens where credentials are silently
sealed under a key that won't survive a pod restart (host-local disk) or that won't
appear in any KMS audit log (random-on-boot).

Prior to #1076, this surfaced as an HTTP 500 on the first `POST /api/v1/credentials`,
often hours after boot. The new contract: in any non-dev profile, titan-server refuses
to start when the credential-key chain yields no key material. The startup log line is:

```
ConfigException: No credential KEK provider is configured and titan.profile != dev.
titan-server refuses to boot in fail-closed mode rather than start an instance that
cannot seal user-submitted secrets. Configure a KMS-backed provider
(e.g. titan-keyprovider-infisical) or set TITAN_KEK for the env provider.
See docs/operations/runbooks/kek-config.md.
```

## Profile gate

`titan.profile` (sysprop) / `TITAN_PROFILE` (env) is intentionally distinct from
`quarkus.profile` so the two signals can move independently:

- The local rig sets `QUARKUS_PROFILE=prod` (so Quarkus uses prod-mode timeouts and
  logging) **and** `TITAN_PROFILE=dev` (so the dev-only KEK provider is permitted).
- A production deployment sets neither — the absence of `TITAN_PROFILE=dev` is enough
  to arm the fail-closed gate.

The gate accepts the value `dev` (case-insensitive, trimmed) and nothing else.
`production`, `prod`, `staging`, the empty string — all refuse.

## Recommended prod path

1. Ship `titan-keyprovider-infisical` (or an equivalent KMS-backed jar) on the
   titan-server classpath. The Infisical provider self-registers via
   `META-INF/services/io.adaptiq.titan.flow.crypto.CredentialKeyProvider`.
2. Set the provider's env vars in the k8s deployment (per `titan-keyprovider-infisical`
   README — typically a project token + project ID).
3. Do **not** set `TITAN_PROFILE`. Do **not** set `LOOP_TITAN_ALLOW_DEV_KEK`. Do **not**
   set `TITAN_CREDENTIALS_DEV_AUTO_KEK`. If any of these leak into the prod manifest,
   the boot-time guards in `DevAutoKeyProvider` and `DevAutoKekBootCheck` will refuse
   to start the pod.

## Rotation

KEK rotation is out of scope for this runbook. Track the rotation system design under
issue #TBD (V1.1). For the v1.0 contract: the KEK is provisioned out-of-band and held
constant for the deployment's lifetime; rotation requires a planned re-encrypt of
`titan.credentials` rows.
