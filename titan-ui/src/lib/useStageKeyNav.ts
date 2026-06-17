/**
 * useStageKeyNav — vim-style keyboard navigation across pipeline stages (closes #688).
 *
 * <p>SREs investigating a multi-stage failure want fast iteration through the
 * failed stages on /builds/$buildId. Mouse-clicking each node in the DAG is
 * slow; j/k feels native. This hook subscribes a single window-level keydown
 * listener and mutates the caller-owned selection state.
 *
 * <h3>Keymap</h3>
 * <ul>
 *   <li>{@code j} / {@code k} — next/prev FAILED stage (no wrap)
 *   <li>{@code Shift+J} / {@code Shift+K} — next/prev stage regardless of status
 *   <li>{@code g g} (within 500ms) — first stage
 *   <li>{@code Shift+G} — last stage
 *   <li>{@code ?} — toggle help overlay (caller renders {@link StageKeyHelpOverlay})
 *   <li>{@code r} — invoke {@code onReplay} (build-level replay; closes #752)
 *   <li>{@code c} — invoke {@code onCancel} (cancel a running build; closes #752)
 *   <li>{@code t} — invoke {@code onRetryStage(selectedId)} when a stage is
 *       selected (per-stage retry; closes #752)
 * </ul>
 *
 * <h3>Disabled contexts</h3>
 * Shortcuts no-op when an INPUT, TEXTAREA, SELECT, or contenteditable element
 * is the active element — so typing in the build search box never selects a
 * stage. Modifier-key combos (Ctrl/Meta/Alt) are also ignored to avoid
 * stomping browser/OS shortcuts.
 *
 * <h3>Action gating</h3>
 * The build-action keys r/c/t intentionally do NOT call preventDefault when
 * the caller hasn't supplied the matching handler. That way, when the action
 * is unavailable (e.g. {@code r} on a SUCCEEDED build whose Replay button is
 * disabled), the keystroke bubbles to the browser and nothing surprising
 * happens. The caller is responsible for omitting the callback when the
 * underlying action is forbidden by role or status preconditions.
 */
import { useCallback, useEffect, useRef, useState } from 'react'
import type { FlowNodeDto } from '@/api/types'

const STAGE_TYPES = new Set(['STAGE', 'PHASE'])
const G_PAIR_WINDOW_MS = 500

/** A flow node is a "stage" if its nodeType matches the rendered timeline rows. */
export function isStageNodeForNav(n: FlowNodeDto): boolean {
  const t = n.nodeType?.toUpperCase() ?? ''
  return STAGE_TYPES.has(t)
}

/** A stage counts as FAILED for j/k purposes if its status string upcases to FAILED. */
function isFailed(n: FlowNodeDto): boolean {
  const s = (n.status ?? '').toUpperCase()
  return s === 'FAILED' || s === 'FAILURE'
}

/**
 * Whether the active element is a text-entry surface and should swallow our
 * single-key shortcuts. Returns false when there is no document (SSR / tests
 * that haven't booted jsdom).
 */
export function isTypingTarget(el: Element | null): boolean {
  if (!el) return false
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  const html = el as HTMLElement
  if (html.isContentEditable === true) return true
  // jsdom doesn't always reflect the `contenteditable` property; fall back
  // to the attribute, which Just Works in tests and is identical to what
  // production browsers expose.
  const attr = html.getAttribute?.('contenteditable')
  if (attr != null && attr !== 'false' && attr !== 'inherit') return true
  return false
}

export interface StageKeyNavOptions {
  /** All flow nodes for the current build. May be undefined while loading. */
  nodes: readonly FlowNodeDto[] | undefined
  /** Currently selected node id, or null if nothing selected. */
  selectedId: string | null
  /** Setter to change selection. Hook will call with a stage nodeId. */
  setSelectedId: (id: string) => void
  /** When false the hook is inert (e.g. during initial load / on other routes). */
  enabled?: boolean
  /**
   * Optional build-level replay handler bound to {@code r}. Caller MUST omit
   * this when the user lacks REPLAY_BUILD or the build is in a non-replayable
   * status — that way the keystroke bubbles unhandled instead of silently
   * failing.
   */
  onReplay?: () => void
  /**
   * Optional cancel handler bound to {@code c}. Caller MUST omit when the
   * build is already terminal (Cancel button would be disabled too).
   */
  onCancel?: () => void
  /**
   * Optional per-stage retry handler bound to {@code t}. Receives the
   * currently-selected stage's nodeId. Hook will NOT invoke this when no
   * stage is selected. Caller should omit when the user lacks the retry
   * permission or the selected stage isn't in a retryable status.
   */
  onRetryStage?: (stageId: string) => void
}

export interface StageKeyNavResult {
  /** Whether the help overlay should be visible. Caller renders the overlay. */
  helpOpen: boolean
  /** Imperatively close the help overlay (e.g. from the overlay's Esc handler). */
  closeHelp: () => void
}

/**
 * Subscribe a window keydown listener for j/k/G/g g/? shortcuts. Returns the
 * help-overlay open state for the caller to render.
 */
export function useStageKeyNav(opts: StageKeyNavOptions): StageKeyNavResult {
  const { nodes, selectedId, setSelectedId, enabled = true, onReplay, onCancel, onRetryStage } = opts
  const [helpOpen, setHelpOpen] = useState(false)
  const lastGAt = useRef<number>(0)

  // Stash latest in refs so the listener doesn't need re-binding per render.
  const nodesRef = useRef<readonly FlowNodeDto[] | undefined>(nodes)
  const selectedRef = useRef<string | null>(selectedId)
  const setRef = useRef(setSelectedId)
  const onReplayRef = useRef<(() => void) | undefined>(onReplay)
  const onCancelRef = useRef<(() => void) | undefined>(onCancel)
  const onRetryRef = useRef<((id: string) => void) | undefined>(onRetryStage)
  nodesRef.current = nodes
  selectedRef.current = selectedId
  setRef.current = setSelectedId
  onReplayRef.current = onReplay
  onCancelRef.current = onCancel
  onRetryRef.current = onRetryStage

  const closeHelp = useCallback(() => setHelpOpen(false), [])

  useEffect(() => {
    if (!enabled) return
    if (typeof window === 'undefined') return

    const onKey = (e: KeyboardEvent) => {
      // Modifier combos belong to the browser/OS, not us. (Shift is allowed —
      // it's part of our keymap.)
      if (e.ctrlKey || e.metaKey || e.altKey) return
      if (isTypingTarget(document.activeElement)) return

      const stages = (nodesRef.current ?? []).filter(isStageNodeForNav)
      const key = e.key

      // ? — toggle help overlay (Shift+/ on most layouts; the resulting key is '?').
      if (key === '?') {
        e.preventDefault()
        setHelpOpen((v) => !v)
        return
      }

      // While the help overlay is open, swallow the rest of the keymap so
      // arrow-style nav doesn't fight with the overlay's own focus.
      if (helpOpen) return

      // Build-level action keys (closes #752). These do NOT require any
      // stages to exist (a degenerate failed build with no DAG can still be
      // replayed/cancelled). They no-op silently — without preventDefault —
      // when the caller hasn't supplied a handler, so the keystroke bubbles
      // and the browser keeps its defaults. Caller is responsible for gating
      // handler presence on role + status.
      if (!e.shiftKey && key === 'r') {
        const cb = onReplayRef.current
        if (cb) {
          e.preventDefault()
          cb()
        }
        return
      }
      if (!e.shiftKey && key === 'c') {
        const cb = onCancelRef.current
        if (cb) {
          e.preventDefault()
          cb()
        }
        return
      }
      if (!e.shiftKey && key === 't') {
        const cb = onRetryRef.current
        const sel = selectedRef.current
        if (cb && sel) {
          e.preventDefault()
          cb(sel)
        }
        return
      }

      // Stage-navigation keys below all require at least one stage.
      if (stages.length === 0) return

      const curId = selectedRef.current
      const curIdx = curId ? stages.findIndex((n) => n.nodeId === curId) : -1

      // Shift+J / Shift+K — next/prev stage regardless of status.
      if (e.shiftKey && (key === 'J' || key === 'K')) {
        if (stages.length === 0) return
        e.preventDefault()
        if (curIdx < 0) {
          setRef.current(stages[0]!.nodeId)
          return
        }
        const next = key === 'J' ? Math.min(curIdx + 1, stages.length - 1) : Math.max(curIdx - 1, 0)
        if (next !== curIdx) setRef.current(stages[next]!.nodeId)
        return
      }

      // Shift+G — last stage.
      if (e.shiftKey && key === 'G') {
        e.preventDefault()
        setRef.current(stages[stages.length - 1]!.nodeId)
        return
      }

      // j / k — next/prev FAILED stage, no wrap.
      if (!e.shiftKey && (key === 'j' || key === 'k')) {
        e.preventDefault()
        const direction = key === 'j' ? 1 : -1
        // Walk from the position adjacent to current.
        const start = curIdx < 0 ? (direction === 1 ? 0 : stages.length - 1) : curIdx + direction
        for (
          let i = start;
          i >= 0 && i < stages.length;
          i += direction
        ) {
          if (isFailed(stages[i]!)) {
            setRef.current(stages[i]!.nodeId)
            return
          }
        }
        // No further failed stage found — no-op (no wrap, per spec).
        return
      }

      // g g — first stage (two presses within window).
      if (!e.shiftKey && key === 'g') {
        e.preventDefault()
        const now = Date.now()
        if (now - lastGAt.current <= G_PAIR_WINDOW_MS) {
          lastGAt.current = 0
          setRef.current(stages[0]!.nodeId)
        } else {
          lastGAt.current = now
        }
        return
      }
    }

    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [enabled, helpOpen])

  return { helpOpen, closeHelp }
}
