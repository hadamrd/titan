# The frontend

`titan-ui/` is the Titan operator UI: a standalone React/Vite single-page
app, built with its own pnpm setup outside the Gradle reactor. This guide
covers how it's built and the UI/UX design bar every change is held to.

## Stack

- **React 19 + TypeScript 5** (strict)
- **Vite 5** build / dev server
- **TanStack Router** (file-based routes under `src/routes/`),
  **TanStack Query** (server state), **TanStack Table** (data grids)
- **Tailwind CSS 3.4** + shadcn-style primitives in `src/components/ui/`
- **Vitest** for unit tests; Playwright (in `e2e/`) for end-to-end

The bundle is served by nginx in the `titan-ui` container and talks to
`titan-server` over REST + SSE.

## Building

The UI is built via `task`, not through Gradle:

```sh
task ui:build        # pnpm install + vite build (the production bundle)
```

For local development:

```sh
cd titan-ui
pnpm install
pnpm dev             # Vite dev server with HMR
pnpm type-check      # tsc --noEmit
pnpm lint            # eslint
pnpm test            # vitest
```

The canonical pre-PR gate is `task verify:ui`, which runs type-check,
lint, vitest, and a real `vite build`. The full build also runs
`tsc -p tsconfig.json --noEmit` — `vite build` only *transpiles* types
(esbuild strips them without checking), so the separate `tsc` pass is what
actually catches type errors. Both run because both classes of error have
reached the trunk before.

### routeTree.gen.ts

The TanStack Router plugin regenerates `src/routeTree.gen.ts` from
`src/routes/` on every build. The file is committed so type-checks pass on
a fresh clone without running Vite first; a committed copy that drifts from
what `pnpm build` regenerates is a gated failure. After adding or removing a
route, rebuild and commit the regenerated tree.

### Runtime configuration

Deployment-specific values (API base URL, OIDC settings) are read at
runtime from `src/runtimeConfig.ts` rather than baked into the bundle at
build time, so one image can serve any rig.

## Design principles

Titan's UI is the product, not a thin admin skin over an API. The mental
image to aim for: **a quiet, dense, mechanical control surface where the
live state of the system is always one glance away.** An instrument panel,
not a dashboard or a CMS.

### Worldview

- **One coherent product.** Every screen looks designed by the same person
  in the same week. No escape hatches, no "raw config" views.
- **Restraint.** One accent colour. One pulsing element on screen at a
  time. Typography does the work colour usually does.
- **Density with purpose.** Dense, but every glyph has a job — a status, a
  count, a delta, a name. Compression is welcome; decoration is not.
- **Data fidelity.** Numbers are exact. Timestamps switch between absolute
  and relative. The user can always drill into the raw value.
- **Keyboard-first, live-state.** A daily-driver tool: actions have
  shortcuts, and the UI shows the system's heartbeat without being twitchy.

### Audience and tone

The primary user is an SRE / release engineer — technical, often under
stress, who needs to know *right now* whether a build is going to pass. So:

- **The first screen answers "is anything on fire?" in under a second** —
  running builds, queue depth, worker health visible up top.
- **Time is the most important data type.** Every page has timestamps;
  absolute vs relative is a global, user-controlled toggle.
- **Errors are calm.** On failure the status dot turns red and the row
  stays put; no banners, no toast storms, no modals. The UI is the steady
  one in the room.
- **Empty states are useful** — a "no builds yet" state explains the next
  step (connect a source) rather than just stating the absence.

### The grounding (visual-design canon)

From the standard visual-design principles:

1. **Scale** — relative size signals importance; the most important
   element is the largest (≤3 sizes per view).
2. **Visual hierarchy** — guide the eye in priority order via size, weight,
   colour, and spacing.
3. **Balance** — visual weight spread across the layout; no large dead
   zones.
4. **Contrast** — dissimilar things look dissimilar; text clears WCAG
   contrast.
5. **Gestalt / proximity** — related things sit close, unrelated things
   sit apart: less space within a group, more between groups.

And the "looks good" techniques: align everything to a grid, ≤2 fonts, a
limited palette, a base spacing unit used in multiples, and the same
treatment for the same purpose on every page.

## Hard constraints (blocking review findings)

A UI change that violates one of these is a blocking finding, not a nit.

- **H1 — Every route renders inside the shared page frame.** Use
  `<PageContainer>` / `<PageHeader>`; centred, consistent `px-6 py-6`
  padding. Never let content hug the top-left of a 1440px viewport.
- **H2 — Reuse primitives; never reinvent.** Tables → `DataTable`/`Table`.
  Buttons → `Button`. Loading → `Skeleton`. Status → `StatusDot`/`Badge`.
  Trend → `Sparkline`. A second table/button/card implementation is a
  reuse violation.
- **H3 — Spacing from the Tailwind scale**, in a consistent rhythm
  (`2/4/6/8/12`, 8px base) — never arbitrary `mt-[13px]`. Tighter within a
  group than between groups.
- **H4 — Every data view handles four states**: loading (`Skeleton`),
  empty (centred message + primary action), error (retry affordance), and
  populated. No permanently-empty columns.
- **H5 — One clear primary action per view**, visually dominant; everything
  else secondary/ghost.
- **H6 — Hierarchy via the type scale**, not ad-hoc sizes: page title →
  section header → body → caption, ≤3 sizes and ≤2 weights. Muted text
  still passes contrast.
- **H7 — Responsive at 768px.** No horizontal scroll, clipped content, or
  overlapping controls; tables scroll inside their container, not the page.
- **H8 — Consistency across pages.** A new page matches sibling pages'
  container, header, table, spacing, and empty-state patterns. Invent a
  new pattern only by extracting a shared component.

### Pre-PR UX checklist

- [ ] Wrapped in `<PageContainer>` + `<PageHeader>`; content is framed (H1)
- [ ] Reused `ui/` primitives; no new table/button/card impl (H2)
- [ ] Spacing from the Tailwind scale; tighter-within / looser-between (H3)
- [ ] loading / empty / error / populated all handled (H4)
- [ ] exactly one dominant primary action (H5)
- [ ] ≤3 type sizes, ≤2 weights, clear hierarchy (H6)
- [ ] checked at 1440px **and** 768px — no overflow/clipping (H7)
- [ ] matches sibling pages; no bespoke one-off pattern (H8)
- [ ] muted/secondary text passes WCAG contrast

## Aligning with engine semantics

The design must align with real platform semantics:

- **The pipeline model is stages × jobs × steps**, with `gate` and
  `parallel` as node types. The build DAG is a vertical column of stages
  with parallel sub-columns — not arbitrary topology.
- **The pipeline YAML schema is the source of truth.** New UI affordances
  must map to schema features that exist (or that we're committing to
  ship). If a design needs a feature the schema lacks, call it out.
- **Workers are pull-based** — a worker polls a queue; the controller never
  pushes. "Drain" means "stop polling new work, finish in-flight." Reflect
  worker autonomy in any diagram (arrow from worker to controller).
- **Auth is OIDC + roles.** When a user lacks a role, reveal the gated
  action as disabled with the reason, rather than hiding it.
- **Secrets are envelope-encrypted.** The UI sees metadata only — never
  plaintext, never sealed blobs.
- **Time is UTC server-side**, displayed in the user's local timezone, with
  the global relative/absolute toggle.
