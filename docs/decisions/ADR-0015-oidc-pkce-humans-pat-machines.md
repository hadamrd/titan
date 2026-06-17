# ADR-0015: OIDC + PKCE for humans, PATs for machines

**Status:** Accepted

**Context** — A standalone CI control plane needs an auth model for both interactive operators (the UI) and non-interactive callers (CLI, scripts, CI integrations). Session cookies and password auth are liabilities for an API-first product, and Titan ships no identity store of its own.

**Decision** — Humans authenticate via OIDC Authorization Code + PKCE (Quarkus OIDC server-side, `oidc-client-ts` in the browser) against an external IdP (Keycloak on the rig); machines authenticate with Personal Access Tokens. No session cookies, no API password auth. Every API endpoint accepts both paths uniformly.

**Consequences**
- Identity, SSO, and user lifecycle are delegated to the IdP; Titan stores roles and grants, not passwords.
- A single endpoint serves both a logged-in operator and a scripted PAT caller, so there is no parallel auth surface to drift.
- The browser never holds a long-lived secret; PKCE protects the code exchange.
- An IdP is a hard runtime dependency for human login (the rig seeds a Keycloak realm).
