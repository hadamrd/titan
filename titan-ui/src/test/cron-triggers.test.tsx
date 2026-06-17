/**
 * Unit tests for the Cron triggers panel on /jobs/$id (tickets #722, #725).
 *
 * Adversarial coverage — empty/non-empty/parse-error/disabled-job, plus a
 * sanity check that the github-only trigger does NOT leak into the cron list
 * (the panel filters the discriminated union by `kind: 'cron'`, not by string
 * sniffing).
 *
 * #725 extension: runtime state from GET /api/v1/jobs/{id}/triggers is
 * surfaced as a relative "last fired" column and an error chip. The hook is
 * mocked so the spec stays a pure component test (no network plumbing).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { CronTriggersPanel } from '../components/CronTriggersPanel'
import type { JobTriggerDto } from '@/api/types'

// Mock the runtime-state hook so each spec injects exactly the rows it cares
// about. The real hook is exercised by the engine IT.
vi.mock('@/api/hooks', () => ({
  useJobTriggers: vi.fn(),
}))

import { useJobTriggers } from '@/api/hooks'
const mockedUseJobTriggers = vi.mocked(useJobTriggers)

function setRuntime(data: JobTriggerDto[] | undefined) {
  // The component only reads `.data` — typed as any to avoid pulling in the
  // full TanStack Query result shape in the test.
  mockedUseJobTriggers.mockReturnValue({ data } as never)
}

function renderPanel(props: Parameters<typeof CronTriggersPanel>[0]) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <CronTriggersPanel {...props} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  // Default: API hasn't returned yet — runtime columns hidden.
  setRuntime(undefined)
})

describe('CronTriggersPanel', () => {
  // #853 UX-cleanup: the panel now collapses entirely when there are no cron
  // schedules and no parse error (returns null). The empty-state and
  // edit-when-no-schedules tests were removed because that surface no longer
  // exists — users add cron via the page-header "Edit triggers" CTA instead.

  it('renders nothing when the pipeline has no triggers block (#853)', () => {
    const { container } = renderPanel({
      jobId: 7,
      pipelineScript: 'stages: []\n',
      jobEnabled: true,
    })
    expect(container.firstChild).toBeNull()
  })

  it('renders nothing when pipelineScript is null (#853)', () => {
    const { container } = renderPanel({
      jobId: 7,
      pipelineScript: null,
      jobEnabled: true,
    })
    expect(container.firstChild).toBeNull()
  })

  it('renders the cron schedule rows when triggers contain cron entries', () => {
    const yaml = [
      'triggers:',
      '  - cron: "0 */6 * * *"',
      '  - cron: "*/15 * * * *"',
      'stages: []',
      '',
    ].join('\n')
    renderPanel({ jobId: 7, pipelineScript: yaml, jobEnabled: true })
    const rows = screen.getAllByTestId('cron-triggers-row')
    expect(rows).toHaveLength(2)
    const exprs = screen.getAllByTestId('cron-triggers-expr').map((e) => e.textContent)
    expect(exprs).toEqual(['0 */6 * * *', '*/15 * * * *'])
    const humanized = screen
      .getAllByTestId('cron-triggers-humanized')
      .map((e) => e.textContent)
    expect(humanized).toEqual(['every 6h', 'every 15m'])
    expect(screen.queryByTestId('cron-triggers-empty')).toBeNull()
  })

  it('filters github triggers out of the cron list (kind-discriminated, not string match)', () => {
    const yaml = [
      'triggers:',
      '  - github:',
      '      branches: [trunk]',
      '  - cron: "30 9 * * *"',
      'stages: []',
      '',
    ].join('\n')
    renderPanel({ jobId: 7, pipelineScript: yaml, jobEnabled: true })
    // Only the cron row should render, not the github trigger.
    const rows = screen.getAllByTestId('cron-triggers-row')
    expect(rows).toHaveLength(1)
    expect(screen.getByTestId('cron-triggers-expr').textContent).toBe('30 9 * * *')
  })

  it('renders the parse-error state on malformed triggers YAML', () => {
    // Inline flow-sequence is rejected by the parser (see lib/triggers.ts).
    renderPanel({
      jobId: 7,
      pipelineScript: 'triggers: [oops not supported]\n',
      jobEnabled: true,
    })
    expect(screen.getByTestId('cron-triggers-parse-error')).toBeInTheDocument()
    expect(screen.queryByTestId('cron-triggers-list')).toBeNull()
    expect(screen.queryByTestId('cron-triggers-empty')).toBeNull()
  })

  // #853 UX-cleanup: "disabled when job disabled" and "Add cron trigger label
  // when empty" tests removed — the panel no longer renders the edit button
  // when there are no schedules (component returns null in that case).

  it('labels the edit button "Edit cron triggers" when schedules exist', () => {
    const yaml = 'triggers:\n  - cron: "0 0 * * *"\nstages: []\n'
    renderPanel({ jobId: 7, pipelineScript: yaml, jobEnabled: true })
    expect(screen.getByTestId('cron-triggers-edit-btn').textContent).toBe(
      'Edit cron triggers',
    )
  })

  // ── #725 runtime-state extensions ────────────────────────────────────────

  it('renders the lastFiredAt column as a relative time when runtime state is present', () => {
    const yaml = 'triggers:\n  - cron: "0 0 * * *"\nstages: []\n'
    const firedAt = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString() // 2h ago
    setRuntime([
      {
        id: 't1',
        type: 'cron',
        expression: '0 0 * * *',
        lastFiredAt: firedAt,
        lastError: null,
        nextFireAt: new Date(Date.now() + 60_000).toISOString(),
      },
    ])
    renderPanel({ jobId: 7, pipelineScript: yaml, jobEnabled: true })
    const cell = screen.getByTestId('cron-triggers-last-fired')
    expect(cell.textContent).toMatch(/\d+h ago|\d+m ago/)
    expect(screen.queryByTestId('cron-triggers-last-error')).toBeNull()
  })

  it('renders the lastError chip when the runtime state carries an error', () => {
    const yaml = 'triggers:\n  - cron: "0 0 * * *"\nstages: []\n'
    setRuntime([
      {
        id: 't1',
        type: 'cron',
        expression: '0 0 * * *',
        lastFiredAt: null,
        lastError: 'queue dispatch refused: worker pool drained',
        nextFireAt: null,
      },
    ])
    renderPanel({ jobId: 7, pipelineScript: yaml, jobEnabled: true })
    const chip = screen.getByTestId('cron-triggers-last-error')
    expect(chip.textContent).toBe('error')
    // Full error message lives in the title for hover-discovery (no overflow).
    expect(chip.getAttribute('title')).toBe(
      'queue dispatch refused: worker pool drained',
    )
    // No fire = no relative-time column.
    expect(screen.queryByTestId('cron-triggers-last-fired')).toBeNull()
  })

  it('hides runtime columns when the API call is in flight (data undefined)', () => {
    const yaml = 'triggers:\n  - cron: "0 0 * * *"\nstages: []\n'
    setRuntime(undefined)
    renderPanel({ jobId: 7, pipelineScript: yaml, jobEnabled: true })
    expect(screen.queryByTestId('cron-triggers-last-fired')).toBeNull()
    expect(screen.queryByTestId('cron-triggers-last-error')).toBeNull()
    // The configured-view columns must still render — runtime state is additive.
    expect(screen.getByTestId('cron-triggers-expr').textContent).toBe('0 0 * * *')
  })
})
