/**
 * Adversarial tests for the /pipelines/validate page (closes #745).
 *
 * Cover the three corners that matter for the close-the-loop contract:
 *   1. submit is disabled when the editor is empty (no DoS-y empty-POST loop);
 *   2. an invalid-verdict server response renders located errors inline;
 *   3. a valid-verdict server response renders the stage / trigger summary;
 *   4. localStorage round-trip: YAML typed in this mount surfaces on the next.
 */
import { describe, it, expect, beforeEach, afterEach, vi, type MockInstance } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react'
import { PipelineValidatePage } from '../routes/pipelines/validate'

const STORAGE_KEY = 'titan.pipelines.validate.yaml'

describe('PipelineValidatePage (issue #745)', () => {
  let fetchSpy: MockInstance<typeof fetch>

  beforeEach(() => {
    localStorage.clear()
    fetchSpy = vi.spyOn(globalThis, 'fetch')
  })

  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('disables the Validate button when the editor is empty', () => {
    render(<PipelineValidatePage />)
    const btn = screen.getByTestId('pipeline-validate-submit') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    // The result pane must NOT be rendered before any submit.
    expect(screen.queryByTestId('pipeline-validate-result')).toBeNull()
  })

  it('enables the button once YAML is typed, and POSTs to /api/v1/pipeline/validate', async () => {
    fetchSpy.mockResolvedValue(
      new Response(
        JSON.stringify({
          valid: true,
          errors: [],
          summary: { stages: [{ name: 'Build', stepCount: 1 }], triggers: [] },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    render(<PipelineValidatePage />)
    const ta = screen.getByTestId('pipeline-yaml-input') as HTMLTextAreaElement
    fireEvent.change(ta, { target: { value: 'stages:\n  - stage: Build\n    steps:\n      - sh: echo hi\n' } })

    const btn = screen.getByTestId('pipeline-validate-submit') as HTMLButtonElement
    expect(btn.disabled).toBe(false)
    fireEvent.click(btn)

    await waitFor(() => expect(fetchSpy).toHaveBeenCalledTimes(1))
    const call = fetchSpy.mock.calls[0]
    expect(String(call[0])).toContain('/api/v1/pipeline/validate')
    const init = call[1] as RequestInit
    expect(init.method).toBe('POST')
    expect(String(init.body)).toContain('stages')
  })

  it('renders the parser errors inline when the server replies valid=false', async () => {
    fetchSpy.mockResolvedValue(
      new Response(
        JSON.stringify({
          valid: false,
          errors: [
            { line: 3, column: 5, message: "unexpected character ':' at line 3" },
            { message: 'pipeline definition must declare stages' },
          ],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    render(<PipelineValidatePage />)
    const ta = screen.getByTestId('pipeline-yaml-input') as HTMLTextAreaElement
    fireEvent.change(ta, { target: { value: 'broken:\n\tx: 1\n' } })
    fireEvent.click(screen.getByTestId('pipeline-validate-submit'))

    const errors = await screen.findAllByTestId('pipeline-validate-error')
    expect(errors).toHaveLength(2)
    // Line locator must appear on the first error.
    expect(errors[0].textContent).toMatch(/line 3/)
    expect(errors[0].textContent).toContain("unexpected character ':' at line 3")
    // The second error has no location — the prefix must NOT inject a phantom line marker.
    expect(errors[1].textContent).not.toMatch(/line \d+/)
    expect(errors[1].textContent).toContain('pipeline definition must declare stages')
    // Success pane must NOT render in parallel.
    expect(screen.queryByTestId('pipeline-validate-valid')).toBeNull()
  })

  it('renders the stage / trigger summary when the server replies valid=true', async () => {
    fetchSpy.mockResolvedValue(
      new Response(
        JSON.stringify({
          valid: true,
          errors: [],
          summary: {
            stages: [
              { name: 'Build', stepCount: 2 },
              { name: 'Test', stepCount: 1 },
            ],
            triggers: [{ type: 'cron', expression: '0 * * * *' }],
          },
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    render(<PipelineValidatePage />)
    fireEvent.change(screen.getByTestId('pipeline-yaml-input'), {
      target: { value: 'stages:\n  - stage: Build\n' },
    })
    fireEvent.click(screen.getByTestId('pipeline-validate-submit'))

    await screen.findByTestId('pipeline-validate-valid')
    const stages = screen.getByTestId('pipeline-validate-stages')
    expect(stages.textContent).toContain('Build')
    expect(stages.textContent).toContain('Test')
    expect(stages.textContent).toContain('2 step(s)')
    expect(stages.textContent).toContain('1 step(s)')

    const triggers = screen.getByTestId('pipeline-validate-triggers')
    expect(triggers.textContent).toContain('cron')
    expect(triggers.textContent).toContain('0 * * * *')
    // Invalid pane must NOT render in parallel.
    expect(screen.queryByTestId('pipeline-validate-invalid')).toBeNull()
  })

  it('persists the editor YAML to localStorage and rehydrates on next mount', async () => {
    const sample = 'stages:\n  - stage: Persisted\n'
    const { unmount } = render(<PipelineValidatePage />)
    fireEvent.change(screen.getByTestId('pipeline-yaml-input'), { target: { value: sample } })
    // Effect fires synchronously after change — assert the storage write landed.
    await waitFor(() => expect(localStorage.getItem(STORAGE_KEY)).toBe(sample))
    unmount()

    // Re-mount — the YAML must come back without any user action.
    render(<PipelineValidatePage />)
    const ta = screen.getByTestId('pipeline-yaml-input') as HTMLTextAreaElement
    await waitFor(() => expect(ta.value).toBe(sample))
  })
})
