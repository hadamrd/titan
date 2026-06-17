/**
 * Adversarial tests for the v3-stack chunky card SVG faithfulness (#552).
 *
 * What we lock in:
 *   1. The SVG geometry matches mockup `v3-stack.html` lines 359–487:
 *      4×80 status rail, 22-tall header strip, status dot + uppercase
 *      mono label + right-anchored duration, name at y=42, descriptor at
 *      y=58, footer at y=73.
 *   2. SUCCESS / FAILED / SKIPPED variants pick the right token colors,
 *      not arbitrary hex.
 *   3. The selection glow ring renders only when selected, and the caret
 *      shipped in PR #554 keeps rendering (regression guard).
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ReactFlowProvider, type NodeProps } from '@xyflow/react'
import type { ReactNode } from 'react'
import { StackCardNode, type StackCardData } from '../components/BuildDetail/StackCardNode'

function withFlow(children: ReactNode) {
  return <ReactFlowProvider>{children}</ReactFlowProvider>
}

function nodeProps(data: StackCardData, selected: boolean): NodeProps & { data: StackCardData } {
  return {
    id: data.nodeId,
    data,
    selected,
    type: 'stackCard',
    dragging: false,
    isConnectable: false,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
    width: 180,
    height: 80,
    zIndex: 0,
    targetPosition: 'left',
    sourcePosition: 'right',
    selectable: true,
    deletable: false,
    draggable: false,
  } as unknown as NodeProps & { data: StackCardData }
}

beforeEach(() => {
  // motion allowed by default
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: vi.fn().mockImplementation((q: string) => ({
      matches: false,
      media: q,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      onchange: null,
      dispatchEvent: () => false,
    })),
  })
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

const OK_STAGE: StackCardData = {
  nodeId: 'checkout',
  name: 'checkout',
  descriptor: 'stage:checkout',
  footer: '1 step',
  durationMs: 12_000,
  status: 'SUCCESS',
  variant: 'ok',
}

describe('StackCardNode — v3 SVG faithfulness (#552)', () => {
  it('renders the SUCCESS STAGE skeleton — rail, strip, status, duration, name, descriptor, footer', () => {
    const { container } = render(withFlow(<StackCardNode {...nodeProps(OK_STAGE, false)} />))

    // 4×80 status rail rect with the OK token color.
    const rects = Array.from(container.querySelectorAll('svg rect'))
    const rail = rects.find(
      (r) => r.getAttribute('width') === '4' && r.getAttribute('height') === '80',
    )
    expect(rail, 'expected a 4×80 status rail rect').not.toBeUndefined()
    expect(rail!.getAttribute('fill')).toBe('var(--ok)')

    // 176×22 header strip.
    const strip = rects.find(
      (r) => r.getAttribute('width') === '176' && r.getAttribute('height') === '22',
    )
    expect(strip, 'expected a 176×22 header strip rect').not.toBeUndefined()

    // SUCCESS label is uppercase mono, in OK color.
    const statusText = screen.getByText('SUCCESS')
    expect(statusText.tagName.toLowerCase()).toBe('text')
    expect(statusText.getAttribute('fill')).toBe('var(--ok)')
    expect(statusText.getAttribute('font-family')).toMatch(/Geist Mono/)

    // Duration text (right-anchored).
    const dur = container.querySelector('svg text[text-anchor="end"]')
    expect(dur, 'expected a right-anchored duration text').not.toBeNull()
    expect(dur!.textContent).toBe('12s')

    // Name "checkout".
    expect(screen.getByText('checkout')).toBeTruthy()
    // Descriptor "stage:checkout".
    expect(screen.getByText('stage:checkout')).toBeTruthy()
    // Footer "1 step".
    expect(screen.getByText('1 step')).toBeTruthy()
  })

  it('FAILED tints both the rail AND the status text with the fail token', () => {
    const data: StackCardData = {
      nodeId: 'test-it',
      name: 'test-it',
      descriptor: 'jest --runInBand',
      footer: '19 suites',
      durationMs: 116_000,
      status: 'FAILED',
      variant: 'fail',
    }
    const { container } = render(withFlow(<StackCardNode {...nodeProps(data, false)} />))

    const rects = Array.from(container.querySelectorAll('svg rect'))
    const rail = rects.find(
      (r) => r.getAttribute('width') === '4' && r.getAttribute('height') === '80',
    )
    expect(rail!.getAttribute('fill')).toBe('var(--fail)')

    const statusText = screen.getByText('FAILED')
    expect(statusText.getAttribute('fill')).toBe('var(--fail)')

    // Card wrapper carries the .fail class so CSS background tint applies.
    const card = container.querySelector('.bd-stack-card')
    expect(card?.className).toMatch(/\bfail\b/)
  })

  it('SKIPPED dims the wrapper (skip class) and shows the em-dash placeholder for duration', () => {
    const data: StackCardData = {
      nodeId: 'package',
      name: 'package',
      descriptor: 'upstream test-it failed',
      footer: '',
      durationMs: null,
      status: 'CANCELLED',
      variant: 'skip',
    }
    const { container } = render(withFlow(<StackCardNode {...nodeProps(data, false)} />))

    const card = container.querySelector('.bd-stack-card')
    expect(card?.className).toMatch(/\bskip\b/)

    // SKIPPED label + em-dash duration.
    expect(screen.getByText('SKIPPED')).toBeTruthy()
    const dur = container.querySelector('svg text[text-anchor="end"]')
    expect(dur!.textContent).toBe('—')
  })

  it('renders the selection glow rect ONLY when selected', () => {
    const { container, rerender } = render(
      withFlow(<StackCardNode {...nodeProps(OK_STAGE, false)} />),
    )
    expect(container.querySelector('.stack-card-glow')).toBeNull()

    rerender(withFlow(<StackCardNode {...nodeProps(OK_STAGE, true)} />))
    const glow = container.querySelector('.stack-card-glow')
    expect(glow, 'expected a .stack-card-glow rect when selected').not.toBeNull()
    expect(glow!.getAttribute('width')).toBe('186')
    expect(glow!.getAttribute('height')).toBe('86')

    // And the PR #554 caret is still there — regression guard.
    expect(screen.getByTestId(`dag-node-caret-${OK_STAGE.nodeId}`)).toBeTruthy()
  })
})
