---
name: ux-tour
description: >-
  Drive the Titan UI like an adversarial reviewer — log into the live rig with
  Playwright, walk an ordered parcours of operator pages, capture strategic
  screenshots (multiple states + widths), score each against a concrete UX
  rubric, and file the worst findings as tickets tagged `ux-tour:finding` +
  `axis:ux-quality`. TRIGGER when the user wants to audit/review the UI, "run a
  UX tour", hunt for sloppy/irregular/random-feeling pages, or check UX quality
  on the rig. SKIP when building or redesigning ONE specific component (use the
  `frontend-design` skill for that — this skill FINDS problems, that one FIXES
  them) and for non-UI work.
---

# Adversarial UX tour

You are a skeptical staff designer doing a hostile walkthrough. Your job is **not**
to be nice — it is to find every page that feels random, sloppy, cramped, or
sparse, and turn each into an actionable ticket. A page that "works" but looks
unconsidered is a finding.

The bar for *good* is the **`frontend-design` skill** — read its SKILL.md once at
the start; this tour flags violations of it.

## 0. Setup (once)

- Rig: `https://titan.test.example.com`. Login: **`dev` / `dev`** (Keycloak
  realm `titan-dev`). If login 404s, the realm seed is broken — that's a
  separate infra bug (`rig/k3s/keycloak/seed-realm.sh`), stop and report it.
- Use the **Playwright MCP** (`mcp__playwright__browser_*`). Navigate, then
  `browser_snapshot` for the a11y tree (cheap, for structure) and
  `browser_take_screenshot` for the pixels (for the vision judgement).
- Capture each page at **two widths**: desktop `1440×900` and narrow `768×900`
  (`browser_resize`). Sloppiness and broken responsiveness hide at the edges.
- For each page also try its **empty / loading / error** states where reachable
  (a filter that returns nothing, an offline tab) — unhandled empty states are
  the most common finding.

## 1. The parcours (ordered — operator-critical first)

Walk these `titan-ui/src/routes/` pages in order. Screenshot each; note state.

| # | Route | What it must do well |
|---|---|---|
| 1 | `/` (index/overview) | dense, scannable status at a glance — not a sea of empty |
| 2 | `/builds` + `/builds/$id` | the list + the detail (logs, stages, verdict) — the golden path |
| 3 | `/jobs` + `/jobs/$id` | list + per-job stats/sparklines/percentiles |
| 4 | `/queue` | live queue depth, claim state |
| 5 | `/workers` | pool health, per-worker state |
| 6 | `/pipelines` | pipeline list + editor |
| 7 | `/approvals` | pending gates — empty state matters |
| 8 | `/audit` + `/rbac-audit` | dense tabular data, filters, pagination |
| 9 | `/integrations` (+ github) | connection cards, install flow |
| 10 | `/profile` + `/settings` | **the known-bad reference** (see §3) — forms + token tables |
| 11 | `/system` | system status |
| 12 | `/admin` | role/user management |
| 13 | `/onboarding`, `/login` | first-impression pages |

Keep the run bounded: **one screenshot pass per page**, don't re-snapshot after
every interaction. Total budget ~15–20 screenshots.

## 2. The rubric — what to flag (each finding cites ≥1 of these)

Score every page. A finding is a **specific, located, fixable** violation —
never "make it nicer."

**A. Space & layout**
- Content marooned in one corner / column while the rest of the canvas is empty
  (the #1 sloppy tell). Does it use the horizontal space, or is everything
  crammed left with a vast dead zone right?
- A small table/form stranded in a huge viewport with no framing, cards, or
  max-width container → looks like a debug page, not a product.
- No consistent content max-width → text lines run absurdly long or float.

**B. Rhythm & alignment (the "feels random" axis)**
- Irregular vertical spacing between sections (some cramped, some gaping).
- Columns/labels not aligned to a grid; gutters inconsistent between rows.
- Mixed left/right/center alignment with no logic → visual noise.

**C. Hierarchy**
- Everything the same weight → no clear primary action / no scannable entry.
- The primary action (e.g. "Generate token") not visually dominant, or buried.
- Section headers indistinguishable from body text.

**D. Affordances & states**
- Unclear what's clickable; ghost/placeholder columns (e.g. a "Last used: –"
  column that's always empty) wasting a column.
- **Missing empty / loading / error states** — a bare table header with no rows,
  no skeleton, no "nothing yet" message.
- Disabled/secondary actions indistinguishable from primary.

**E. Consistency across pages**
- This page's table/card/spacing patterns differ from sibling pages → the app
  feels assembled by different people. Cross-reference what you saw earlier.

**F. Density & type**
- Too sparse (sloppy) OR too dense (unscannable). Tiny text; inconsistent font
  sizes/weights for the same role.

**G. Responsiveness**
- At 768px: overflow, clipped content, horizontal scroll, overlapping elements,
  controls that fall off.

Cheap structural cross-checks (complement the vision pass, don't replace it):
`browser_evaluate` to detect horizontal overflow
(`document.documentElement.scrollWidth > innerWidth`), count empty table bodies,
or check computed contrast on muted text.

## 3. The reference offender (calibrate your eye)

The `/profile#tokens` (Access tokens) page is the canonical "feels random":
a narrow token table + scopes checkboxes stranded top-left in a huge dark
canvas; ~60% of the width is dead space; an always-empty "Last used" column;
weak hierarchy between "Generate token" and the table; irregular gaps. If a page
looks like that, it's a finding. Use it to anchor severity — that page is a
**sev2** ("looks unconsidered, ships anyway").

## 4. Flag findings as tickets

For each finding worth fixing (cap **~5 per run** — the worst, not every nit):

```
gh issue create \
  --title "ux: <page> — <one-line concrete problem>" \
  --label "ux-tour:finding,axis:ux-quality,theme:ui" \
  --body "<body per template below>"
```

Body template (every field required — no vague tickets):
- **Page / route + state**: e.g. `/profile#tokens (populated, 1440px)`
- **Screenshot**: attach/path the captured PNG.
- **Rubric violated**: cite the letter(s), e.g. `A (space), C (hierarchy)`.
- **What's wrong** (a/b): what it looks like now vs. what good looks like.
- **Concrete fix**: e.g. "wrap content in `max-w-5xl` centered container; drop
  the empty Last-used column; make Generate-token a primary button row above a
  bordered card." Reference `frontend-design` principles.
- **Severity**: sev1 (broken/unusable state) / sev2 (sloppy, ships anyway) /
  sev3 (nit).

Don't file duplicates: `gh issue list --label ux-tour:finding` first; if the
same page+rubric is already open, skip or comment.

## 5. Output

End with a short tour report: pages walked, screenshots taken, findings filed
(with #), and the 1–2 highest-leverage UX themes seen across pages (e.g.
"no shared page-container/max-width → everything floats"). That cross-cutting
theme is often worth one structural ticket that fixes many pages at once.

**Why this skill exists:** the loop ships UI that passes tests but looks
unconsidered (Axis-4, EPIC #1115). Tests assert behaviour, never taste. This
tour is the recurring taste-check — run it periodically; the `ux-tour:finding`
label lets the loop pick the findings up as normal backlog.
