/**
 * Global ⌘K command palette (closes #687, supersedes #570 client-filter shape).
 *
 * <p>Linear/Raycast-style modal mounted once in the root layout. Hotkey wiring
 * lives in {@link useCmdK} (⌘K on macOS, Ctrl+K elsewhere); {@code Esc} closes;
 * arrow keys / Enter navigate and select (delegated to {@link cmdk}).
 *
 * <p>Sections — order matters per #687 spec:
 * <ol>
 *   <li><b>Pages</b> — always visible. Static nav shortcuts to the top-level
 *       routes. NO fetch.</li>
 *   <li><b>Jobs</b> — visible only when the query is non-empty. Debounced
 *       300 ms hit on {@code /api/v1/jobs?search=&limit=8}
 *       ({@link useJobsSearch}).</li>
 *   <li><b>Builds</b> — visible only when the query is non-empty. Debounced
 *       300 ms hit on {@code /api/v1/builds?search=&limit=8}
 *       ({@link useFilteredBuilds}, the filterable endpoint shipped in #685).</li>
 * </ol>
 *
 * <p>The empty-query state intentionally fires zero network calls — both
 * search hooks are guarded with {@code enabled} on a trimmed-non-empty
 * predicate. Adversarial coverage in
 * {@code src/test/cmd-palette.test.tsx}.
 */
import { useCallback, useMemo, useState } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { Command } from 'cmdk'
import {
  Activity,
  FileCheck,
  GitBranch,
  Hammer,
  Home,
  Layers,
  ListChecks,
  Server,
  Settings as SettingsIcon,
  User,
} from 'lucide-react'
import { useFilteredBuilds, useJobsSearch } from '@/api/hooks'
import { buildDisplayLabel } from '@/api/types'
import { useCmdK } from '@/lib/useCmdK'
import { useDebouncedValue } from '@/lib/useDebouncedValue'

/** Debounce window for the jobs + builds search hooks. */
const SEARCH_DEBOUNCE_MS = 300

interface PageEntry {
  id: string
  label: string
  to: string
  icon: typeof Home
}

const PAGES: readonly PageEntry[] = [
  { id: 'page:home', label: 'Home', to: '/', icon: Home },
  { id: 'page:pipelines', label: 'Pipelines', to: '/pipelines', icon: Layers },
  { id: 'page:builds', label: 'Builds', to: '/builds', icon: ListChecks },
  {
    id: 'page:pipelines-validate',
    label: 'Validate pipeline YAML',
    to: '/pipelines/validate',
    icon: FileCheck,
  },
  { id: 'page:workers', label: 'Workers', to: '/workers', icon: Server },
  { id: 'page:system', label: 'System', to: '/system', icon: SettingsIcon },
  { id: 'page:profile', label: 'Profile', to: '/profile', icon: User },
]

export interface CommandPaletteProps {
  /** Test seam: forces the modal open at mount. */
  defaultOpen?: boolean
}

export function CommandPalette({ defaultOpen = false }: CommandPaletteProps) {
  const { open, setOpen } = useCmdK({ defaultOpen })
  const [query, setQuery] = useState('')
  const navigate = useNavigate()

  // Debounce the query so each keystroke does NOT trigger a fetch.
  const debounced = useDebouncedValue(query, SEARCH_DEBOUNCE_MS)
  const trimmed = debounced.trim()
  const hasQuery = trimmed.length > 0

  // ── Search data sources — both disabled while query is empty ──────────────
  const { data: jobsPage } = useJobsSearch(trimmed, 8)
  const { data: buildsPage } = useFilteredBuilds(
    hasQuery ? { search: trimmed, limit: 8 } : {},
  )

  // Client-side filter over the jobs response: the server ignores the
  // ?search= param today (back-compat — unknown params pass through), so we
  // re-filter here against fullName / displayName until a server-side filter
  // lands. Cap is the server-imposed limit; we additionally slice to 8 for
  // a defensible UI cap.
  const jobMatches = useMemo(() => {
    if (!hasQuery) return []
    const q = trimmed.toLowerCase()
    return (jobsPage?.items ?? [])
      .filter((j) => {
        const display = (j.displayName ?? '').toLowerCase()
        const full = j.fullName.toLowerCase()
        return display.includes(q) || full.includes(q)
      })
      .slice(0, 8)
  }, [jobsPage, hasQuery, trimmed])

  const buildMatches = useMemo(() => {
    if (!hasQuery) return []
    // #768 — the server-side ?search= is the primary filter (it scans
    // displayName + buildNumber). We pass server results through unchanged so
    // that any backend match (numeric "42", "#42", "deploy-prod-v2.3.1") is
    // surfaced. The label rendering in the Item below uses the
    // `buildDisplayLabel` fallback chain so the override name (or "#N") shows
    // up consistently.
    return (buildsPage?.items ?? []).slice(0, 8)
  }, [buildsPage, hasQuery])

  const close = useCallback(() => {
    setOpen(false)
    setQuery('')
  }, [setOpen])

  const selectPage = useCallback(
    (to: string) => {
      close()
      void navigate({ to })
    },
    [close, navigate],
  )

  const selectJob = useCallback(
    (jobId: number) => {
      close()
      void navigate({ to: '/pipelines/$pipelineId', params: { pipelineId: String(jobId) } })
    },
    [close, navigate],
  )

  const selectBuild = useCallback(
    (buildId: number) => {
      close()
      void navigate({ to: '/builds/$buildId', params: { buildId: String(buildId) } })
    },
    [close, navigate],
  )

  if (!open) return null

  return (
    <>
      <div
        className="cmdk-backdrop"
        role="presentation"
        onClick={close}
        data-testid="cmdk-backdrop"
      />
      <div
        className="cmdk-shell"
        role="dialog"
        aria-modal="true"
        aria-label="Command palette"
      >
        <Command
          label="Command palette"
          shouldFilter={false}
          onKeyDown={(e) => {
            if (e.key === 'Escape') {
              e.preventDefault()
              close()
            }
          }}
        >
          <div className="cmdk-input-row">
            <Command.Input
              autoFocus
              placeholder="Search pipelines and builds, or jump to a page…"
              value={query}
              onValueChange={setQuery}
              data-testid="cmdk-input"
            />
            <kbd className="cmdk-esc">esc</kbd>
          </div>
          <Command.List>
            <Command.Empty>No results.</Command.Empty>

            <Command.Group heading="Pages">
              {PAGES.map((p) => {
                const Icon = p.icon
                return (
                  <Command.Item
                    key={p.id}
                    value={`page ${p.label}`}
                    role="option"
                    onSelect={() => selectPage(p.to)}
                    data-testid={`cmdk-${p.id}`}
                  >
                    <Icon size={14} aria-hidden />
                    <span>{p.label}</span>
                  </Command.Item>
                )
              })}
            </Command.Group>

            {hasQuery && jobMatches.length > 0 && (
              <Command.Group heading="Pipelines">
                {jobMatches.map((j) => {
                  const label = j.displayName ?? j.fullName
                  return (
                    <Command.Item
                      key={`job-${j.id}`}
                      value={`job ${label} ${j.fullName}`}
                      role="option"
                      onSelect={() => selectJob(j.id)}
                      data-testid={`cmdk-job-${j.id}`}
                    >
                      <Hammer size={14} aria-hidden />
                      <span>{label}</span>
                      <span className="cmdk-tag">{j.fullName}</span>
                    </Command.Item>
                  )
                })}
              </Command.Group>
            )}

            {hasQuery && buildMatches.length > 0 && (
              <Command.Group heading="Builds">
                {buildMatches.map((b) => {
                  // #768 — prefer the human-friendly setBuildName override;
                  // fall back to "#N" via the canonical helper. The cmdk Item
                  // `value` includes BOTH so its built-in keyboard filter
                  // (when enabled) matches either token; we render the
                  // friendly label and tag with "#N" for disambiguation.
                  const label = buildDisplayLabel(b)
                  return (
                    <Command.Item
                      key={`build-${b.id}`}
                      value={`build ${b.id} ${b.buildNumber} ${label}`}
                      role="option"
                      onSelect={() => selectBuild(b.id)}
                      data-testid={`cmdk-build-${b.id}`}
                    >
                      <GitBranch size={14} aria-hidden />
                      <span>{label}</span>
                      <span className="cmdk-tag tabnum">#{b.id}</span>
                    </Command.Item>
                  )
                })}
              </Command.Group>
            )}

            {hasQuery && jobMatches.length === 0 && buildMatches.length === 0 && (
              <div className="cmdk-status" aria-live="polite">
                <Activity size={12} aria-hidden />
                <span>Searching…</span>
              </div>
            )}
          </Command.List>
        </Command>
      </div>
    </>
  )
}
