# ADR-0017: React + Vite + TanStack UI

**Status:** Accepted

**Context** — Titan treats the UI as the product: a dense, calm, daily-driver instrument for SREs, not an afterthought bolted onto a server-rendered admin page. It is a standalone single-page app with its own build, consuming the server's REST/SSE API.

**Decision** — `titan-ui` is a standalone SPA: Vite + React 19 + TypeScript 5 (strict), TanStack Router (file-based), TanStack Query, and TanStack Table, with Tailwind and shadcn/ui primitives, tested with Vitest and Playwright. It builds on its own pnpm/Vite toolchain (`task ui:build`), outside the Gradle reactor.

**Consequences**
- The frontend evolves on JS-ecosystem tooling independent of the Java build.
- Type-safe routing, server-state caching, and table virtualization come from a coherent first-party stack rather than ad-hoc libraries.
- Every backend feature ships its UI seam in the same sprint; backend-only work accrues integration-seam debt.
- The app is served as static assets behind nginx (which owns CSP); the UI must not declare CSP in `index.html`.
