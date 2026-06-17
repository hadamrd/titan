# Contributing to titan-ui

## Lint

`titan-ui` is linted by ESLint (flat config: `eslint.config.js`). Run it
directly with:

```bash
pnpm lint           # from titan-ui/
task ui:lint        # from repo root
```

Lint also runs as part of `task verify:ui`, the canonical pre-PR gate
that CI executes.

### Forbidden: native browser dialogs

The following are lint **errors** anywhere under `titan-ui/src/**`:

| Forbidden                            | Use instead                                   |
| ------------------------------------ | --------------------------------------------- |
| `window.confirm(...)` / `confirm(...)` | [`ConfirmDialog`](./src/components/ui/ConfirmDialog.tsx) |
| `window.alert(...)` / `alert(...)`     | `ConfirmDialog` (single-button) or a themed banner |
| `window.prompt(...)` / `prompt(...)`   | A themed input component                      |

**Why:** native browser dialogs render outside the React tree, ignore the
design system, miss the dark theme, and look unbranded next to the rest
of the product. PR #1037 had to swap a `window.confirm()` in
`profile.tsx` for a themed `ConfirmDialog` after the native popup was
caught in prod; PR #1040 (this rule) closes the gap by forbidding the
pattern at lint time.

**How the rule works:**

- `no-restricted-globals` errors on bare `confirm` / `alert` / `prompt`
  identifiers (they resolve to `window.*` via the global scope).
- `no-restricted-syntax` errors on the explicit `window.confirm(...)` /
  `window.alert(...)` / `window.prompt(...)` member-expression form.

**Migration recipe** — replace this:

```tsx
const ok = window.confirm('Really do the thing?')
if (!ok) return
doTheThing()
```

with this:

```tsx
const [open, setOpen] = useState(false)

<button onClick={() => setOpen(true)}>Do the thing</button>

<ConfirmDialog
  open={open}
  title="Do the thing"
  message="Really do the thing?"
  confirmLabel="Do it"
  destructive
  busy={mut.isPending}
  onConfirm={() => mut.mutate(undefined, { onSettled: () => setOpen(false) })}
  onCancel={() => !mut.isPending && setOpen(false)}
  testId="thing-confirm"
/>
```

The dialog is state-prop driven (not Promise-based) — that keeps it
React-idiomatic, plays nicely with mutation state via `busy`, and gives
e2e/vitest tests deterministic `data-testid` hooks.

### Out of scope for this rule

- Native HTTP basic-auth dialogs (those are browser-level, not under UI
  control).
- A Promise-based `ConfirmDialog` wrapper — by design we keep the API
  state-prop driven.
