/**
 * Adversarial tests for useStageKeyNav (#688).
 *
 * <p>Focuses on the sad-path matrix from the issue:
 * <ul>
 *   <li>j on last failed stage stays put (no wrap)
 *   <li>j skips SUCCESS stages when hunting next FAILED
 *   <li>Shift+J advances regardless of status
 *   <li>'?' toggles overlay state
 *   <li>shortcuts disabled while INPUT or contenteditable is focused
 * </ul>
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useStageKeyNav } from '@/lib/useStageKeyNav'
import type { FlowNodeDto } from '@/api/types'

function stage(id: string, status: string): FlowNodeDto {
  return {
    buildId: 1,
    nodeId: id,
    parentIds: null,
    nodeType: 'STAGE',
    displayName: id,
    stepDescriptor: null,
    status,
    agentLabel: null,
    startedAt: null,
    completedAt: null,
    durationMs: null,
    attempt: 1,
    maxAttempts: 1,
    failureCategory: null,
    failureReason: null,
    logTaskId: null,
  }
}

function dispatch(key: string, shiftKey = false) {
  act(() => {
    window.dispatchEvent(new KeyboardEvent('keydown', { key, shiftKey, bubbles: true }))
  })
}

interface Harness {
  nodes: FlowNodeDto[]
  selectedId: string | null
  setSelectedId: ReturnType<typeof vi.fn>
}

function makeHarness(nodes: FlowNodeDto[], initial: string | null = null): Harness {
  return { nodes, selectedId: initial, setSelectedId: vi.fn() }
}

interface ActionOpts {
  onReplay?: () => void
  onCancel?: () => void
  onRetryStage?: (id: string) => void
}

function renderNav(h: Harness, selectedId: string | null = h.selectedId, actions: ActionOpts = {}) {
  return renderHook(({ sel }) =>
    useStageKeyNav({
      nodes: h.nodes,
      selectedId: sel,
      setSelectedId: h.setSelectedId,
      ...actions,
    }),
    { initialProps: { sel: selectedId } },
  )
}

beforeEach(() => {
  // Ensure body has focus so document.activeElement isn't a lingering input from a prior test.
  document.body.innerHTML = ''
  ;(document.body as HTMLElement).focus()
})

afterEach(() => {
  document.body.innerHTML = ''
})

describe('useStageKeyNav', () => {
  it('j on the last failed stage is a no-op (no wrap)', () => {
    const h = makeHarness([
      stage('a', 'SUCCESS'),
      stage('b', 'FAILED'),
      stage('c', 'SUCCESS'),
    ], 'b')
    renderNav(h, 'b')
    dispatch('j')
    expect(h.setSelectedId).not.toHaveBeenCalled()
  })

  it('j skips SUCCESS stages and lands on the next FAILED', () => {
    const h = makeHarness([
      stage('a', 'FAILED'),
      stage('b', 'SUCCESS'),
      stage('c', 'SUCCESS'),
      stage('d', 'FAILED'),
    ], 'a')
    renderNav(h, 'a')
    dispatch('j')
    expect(h.setSelectedId).toHaveBeenCalledWith('d')
  })

  it('k walks backwards to the previous FAILED stage', () => {
    const h = makeHarness([
      stage('a', 'FAILED'),
      stage('b', 'SUCCESS'),
      stage('c', 'FAILED'),
    ], 'c')
    renderNav(h, 'c')
    dispatch('k')
    expect(h.setSelectedId).toHaveBeenCalledWith('a')
  })

  it('Shift+J advances to the next stage regardless of status', () => {
    const h = makeHarness([
      stage('a', 'FAILED'),
      stage('b', 'SUCCESS'),
      stage('c', 'SUCCESS'),
    ], 'a')
    renderNav(h, 'a')
    dispatch('J', true)
    expect(h.setSelectedId).toHaveBeenCalledWith('b')
  })

  it('Shift+K moves back one stage regardless of status', () => {
    const h = makeHarness([
      stage('a', 'SUCCESS'),
      stage('b', 'SUCCESS'),
      stage('c', 'FAILED'),
    ], 'c')
    renderNav(h, 'c')
    dispatch('K', true)
    expect(h.setSelectedId).toHaveBeenCalledWith('b')
  })

  it('Shift+G jumps to the last stage', () => {
    const h = makeHarness([
      stage('a', 'SUCCESS'),
      stage('b', 'SUCCESS'),
      stage('c', 'SUCCESS'),
    ], 'a')
    renderNav(h, 'a')
    dispatch('G', true)
    expect(h.setSelectedId).toHaveBeenCalledWith('c')
  })

  it('g g (within window) jumps to the first stage', () => {
    const h = makeHarness([
      stage('a', 'SUCCESS'),
      stage('b', 'SUCCESS'),
      stage('c', 'FAILED'),
    ], 'c')
    renderNav(h, 'c')
    dispatch('g')
    dispatch('g')
    expect(h.setSelectedId).toHaveBeenCalledWith('a')
  })

  it('single g does NOT trigger first-stage jump', () => {
    const h = makeHarness([stage('a', 'SUCCESS'), stage('b', 'FAILED')], 'b')
    renderNav(h, 'b')
    dispatch('g')
    expect(h.setSelectedId).not.toHaveBeenCalled()
  })

  it("'?' toggles the help overlay open state", () => {
    const h = makeHarness([stage('a', 'FAILED')], 'a')
    const { result } = renderNav(h, 'a')
    expect(result.current.helpOpen).toBe(false)
    dispatch('?')
    expect(result.current.helpOpen).toBe(true)
    dispatch('?')
    expect(result.current.helpOpen).toBe(false)
  })

  it('shortcuts are inert while an INPUT is focused', () => {
    const input = document.createElement('input')
    input.type = 'text'
    document.body.appendChild(input)
    input.focus()
    expect(document.activeElement).toBe(input)

    const h = makeHarness([stage('a', 'FAILED'), stage('b', 'FAILED')], 'a')
    renderNav(h, 'a')
    dispatch('j')
    expect(h.setSelectedId).not.toHaveBeenCalled()
  })

  it('shortcuts are inert while a TEXTAREA is focused', () => {
    const ta = document.createElement('textarea')
    document.body.appendChild(ta)
    ta.focus()
    expect(document.activeElement).toBe(ta)

    const h = makeHarness([stage('a', 'FAILED'), stage('b', 'FAILED')], 'a')
    renderNav(h, 'a')
    dispatch('j')
    expect(h.setSelectedId).not.toHaveBeenCalled()
  })

  it('shortcuts are inert while a contenteditable element is focused', () => {
    const div = document.createElement('div')
    div.setAttribute('contenteditable', 'true')
    // jsdom needs tabindex for div focus to take.
    div.tabIndex = 0
    document.body.appendChild(div)
    div.focus()
    expect(document.activeElement).toBe(div)

    const h = makeHarness([stage('a', 'FAILED'), stage('b', 'FAILED')], 'a')
    renderNav(h, 'a')
    dispatch('j')
    expect(h.setSelectedId).not.toHaveBeenCalled()
  })

  it('ignores keydowns with Ctrl/Meta modifiers (lets browser handle them)', () => {
    const h = makeHarness([stage('a', 'FAILED'), stage('b', 'FAILED')], 'a')
    renderNav(h, 'a')
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', ctrlKey: true }))
    })
    act(() => {
      window.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', metaKey: true }))
    })
    expect(h.setSelectedId).not.toHaveBeenCalled()
  })

  it('non-STAGE node types are skipped (only stages count)', () => {
    // Mix a STEP node in between two stages — should be invisible to nav.
    const stepNode: FlowNodeDto = { ...stage('s', 'FAILED'), nodeType: 'STEP' }
    const h = makeHarness([stage('a', 'FAILED'), stepNode, stage('b', 'FAILED')], 'a')
    renderNav(h, 'a')
    dispatch('j')
    expect(h.setSelectedId).toHaveBeenCalledWith('b')
  })

  // ── Build-action shortcuts (#752) ─────────────────────────────────────────

  it("'r' invokes onReplay when handler is defined", () => {
    const onReplay = vi.fn()
    const h = makeHarness([stage('a', 'FAILED')], 'a')
    renderNav(h, 'a', { onReplay })
    dispatch('r')
    expect(onReplay).toHaveBeenCalledTimes(1)
  })

  it("'r' is a no-op when onReplay is undefined (e.g. SUCCEEDED build)", () => {
    // No handler supplied: the keystroke must NOT throw and must NOT call
    // setSelectedId — the absence of a callback is how the route signals
    // "replay isn't allowed in this state".
    const h = makeHarness([stage('a', 'SUCCESS')], 'a')
    renderNav(h, 'a', {})
    expect(() => dispatch('r')).not.toThrow()
    expect(h.setSelectedId).not.toHaveBeenCalled()
  })

  it("'c' invokes onCancel when handler is defined", () => {
    const onCancel = vi.fn()
    const h = makeHarness([stage('a', 'RUNNING')], 'a')
    renderNav(h, 'a', { onCancel })
    dispatch('c')
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it("'c' is a no-op on a finished build (no handler supplied)", () => {
    const h = makeHarness([stage('a', 'SUCCESS')], 'a')
    renderNav(h, 'a', {})
    expect(() => dispatch('c')).not.toThrow()
  })

  it("'t' with a selected stage invokes onRetryStage(stageId)", () => {
    const onRetryStage = vi.fn()
    const h = makeHarness([stage('a', 'FAILED'), stage('b', 'FAILED')], 'b')
    renderNav(h, 'b', { onRetryStage })
    dispatch('t')
    expect(onRetryStage).toHaveBeenCalledTimes(1)
    expect(onRetryStage).toHaveBeenCalledWith('b')
  })

  it("'t' on a selected FAILED stage invokes the retry mutation (route wiring contract, #757)", () => {
    // Mirrors the route's gating: handler is supplied only when the caller
    // confirmed the selected stage is FAILED and the user holds REPLAY_BUILD
    // (the route does that check; the hook just delegates). Press 't' →
    // mutation fires with the selected stage id.
    const retryMutate = vi.fn()
    const onRetryStage = (id: string) => retryMutate({ stageId: id })
    const h = makeHarness(
      [stage('a', 'SUCCESS'), stage('b', 'FAILED'), stage('c', 'FAILED')],
      'b',
    )
    renderNav(h, 'b', { onRetryStage })
    dispatch('t')
    expect(retryMutate).toHaveBeenCalledTimes(1)
    expect(retryMutate).toHaveBeenCalledWith({ stageId: 'b' })
  })

  it("'t' is a bubble-up no-op when the route withholds the handler (e.g. non-FAILED selection / missing role)", () => {
    // The route MUST omit onRetryStage when the selected stage isn't FAILED
    // OR the user lacks REPLAY_BUILD. The hook can't see those preconditions;
    // it just sees an undefined callback and must no-op silently.
    const h = makeHarness([stage('a', 'SUCCESS')], 'a')
    renderNav(h, 'a', {}) // no onRetryStage supplied
    expect(() => dispatch('t')).not.toThrow()
    expect(h.setSelectedId).not.toHaveBeenCalled()
  })

  it("'t' with NO selected stage does NOT invoke onRetryStage", () => {
    const onRetryStage = vi.fn()
    const h = makeHarness([stage('a', 'FAILED')], null)
    renderNav(h, null, { onRetryStage })
    dispatch('t')
    expect(onRetryStage).not.toHaveBeenCalled()
  })

  it("'r' inside an input is suppressed by the typing guard", () => {
    const input = document.createElement('input')
    input.type = 'text'
    document.body.appendChild(input)
    input.focus()

    const onReplay = vi.fn()
    const h = makeHarness([stage('a', 'FAILED')], 'a')
    renderNav(h, 'a', { onReplay })
    dispatch('r')
    expect(onReplay).not.toHaveBeenCalled()
  })

  it('build-action keys are inert while the help overlay is open', () => {
    // Opening the overlay must not let r/c/t leak through; otherwise pressing
    // 'r' while reading the cheatsheet would silently replay the build.
    const onReplay = vi.fn()
    const onCancel = vi.fn()
    const onRetryStage = vi.fn()
    const h = makeHarness([stage('a', 'FAILED')], 'a')
    renderNav(h, 'a', { onReplay, onCancel, onRetryStage })
    dispatch('?') // open overlay
    dispatch('r')
    dispatch('c')
    dispatch('t')
    expect(onReplay).not.toHaveBeenCalled()
    expect(onCancel).not.toHaveBeenCalled()
    expect(onRetryStage).not.toHaveBeenCalled()
  })

  it('Shift+R / Shift+C / Shift+T do NOT trigger the build actions', () => {
    // Only the bare lowercase form is bound — uppercase would clash with
    // potential future Shift-prefixed nav (and would surprise users).
    const onReplay = vi.fn()
    const onCancel = vi.fn()
    const onRetryStage = vi.fn()
    const h = makeHarness([stage('a', 'FAILED')], 'a')
    renderNav(h, 'a', { onReplay, onCancel, onRetryStage })
    dispatch('R', true)
    dispatch('C', true)
    dispatch('T', true)
    expect(onReplay).not.toHaveBeenCalled()
    expect(onCancel).not.toHaveBeenCalled()
    expect(onRetryStage).not.toHaveBeenCalled()
  })
})
