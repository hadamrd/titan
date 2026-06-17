# ADR-0016: Envelope-encrypted secrets via a pluggable backend

**Status:** Accepted

**Context** — Pipelines reference credentials (registry tokens, deploy keys, API secrets). Storing those as plaintext in a `config_json` column or any non-credential column is unacceptable, and key rotation must not require re-encrypting every stored payload. Different operators want different key-management backends.

**Decision** — Secrets are stored with envelope encryption: each secret is encrypted under a per-secret Data Encryption Key (DEK), and each DEK is wrapped under a Key Encryption Key (KEK). The `db-envelope` backend ships by default; the KEK provider and the secrets backend are both `ServiceLoader` SPIs (Infisical and Vault are drop-in modules). Secrets are reachable only through the credentials service — never plaintext in `config_json` or any other column.

**Consequences**
- KEK rotation re-wraps only the DEKs; the encrypted payloads are untouched, so rotation is cheap.
- Plaintext secrets in any non-credential column is a flagged anti-pattern that fails review.
- Operators can move the trust root to an external KMS (Infisical/Vault) without changing the storage format.
- Credential and sealed values are never cached, including in the Caffeine hot-path caches.
