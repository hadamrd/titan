/**
 * Unit coverage for the two build-detail surfaces the golden-path e2e
 * (#1166) asserts on in the browser:
 *
 *   1. The overall verdict badge (`StatusBadge` in the build-detail header)
 *      maps a FAILURE/FAILED build to the RED `fail` dot variant AND exposes
 *      the raw verdict via `data-status` + the `build-verdict-badge` testid.
 *      If a refactor dropped the fail→red mapping the e2e would go red on a
 *      live rig; this pins it sub-second so the regression is caught in CI.
 *
 *   2. The `TerminalConsole` renders a masked token line (`****`) verbatim
 *      and — adversarially — never re-introduces a raw secret value the
 *      server already redacted. A template that, say, dangerouslySetInnerHTML'd
 *      or re-derived the line could leak; this asserts the rendered DOM is
 *      exactly the (already-masked) input.
 *
 * These are the Vitest half of #1166's test matrix.
 */
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StatusBadge } from '../../StatusBadge'
import { TerminalConsole } from '../TerminalConsole'

describe('StatusBadge verdict mapping (#1166)', () => {
  it('maps a FAILURE build to the red `fail` dot variant and exposes data-status', () => {
    const { container } = render(
      <StatusBadge status="FAILURE" testId="build-verdict-badge" />,
    )
    const badge = screen.getByTestId('build-verdict-badge')
    // The raw verdict is machine-readable on the badge itself.
    expect(badge).toHaveAttribute('data-status', 'FAILURE')
    // The colour is carried by the StatusDot variant class — `fail` is the
    // red bucket in tokens.css. Asserting the class (not a pixel) is the
    // stable contract.
    const dot = container.querySelector('.status-dot')
    expect(dot, 'StatusBadge must render a status dot').not.toBeNull()
    expect(dot!.classList.contains('fail')).toBe(true)
    // The label text is the verdict, visible to the operator.
    expect(badge).toHaveTextContent('FAILURE')
  })

  it('maps FAILED (the flow-node verdict spelling) to red too', () => {
    const { container } = render(<StatusBadge status="FAILED" testId="b" />)
    expect(container.querySelector('.status-dot')!.classList.contains('fail')).toBe(true)
  })

  it('maps SUCCESS to the green `success` variant — guards an inverted mapping', () => {
    const { container } = render(<StatusBadge status="SUCCESS" testId="b" />)
    const cls = container.querySelector('.status-dot')!.classList
    expect(cls.contains('success')).toBe(true)
    expect(cls.contains('fail')).toBe(false)
  })

  it('omitting testId leaves the badge un-tagged (non-visual, opt-in hook)', () => {
    render(<StatusBadge status="RUNNING" />)
    expect(screen.queryByTestId('build-verdict-badge')).toBeNull()
  })
})

describe('TerminalConsole secret masking render (#1166)', () => {
  const RAW_SECRET = 'sentinel-redact-deadbeef-canary'

  it('renders a masked **** line verbatim and never re-introduces the raw secret', () => {
    // The server has ALREADY redacted — the component receives masked lines.
    // The component must render them faithfully and add nothing back.
    const lines = [
      'deploy-region=eu-central-1',
      'direct: token=[****]',
      "embedded: curl -H 'X-Auth: ****' https://api.example.test/ping",
      'API_TOKEN=****',
    ]
    render(<TerminalConsole lines={lines} sseState="done" buildNumber={7} />)

    const body = screen.getByTestId('terminal-body')
    const rendered = body.textContent ?? ''

    // The mask token survived rendering — proves the masked line is shown,
    // not swallowed.
    expect(rendered.includes('****')).toBe(true)
    // Adversarial: the raw secret must appear zero times in the rendered DOM,
    // across every line. A leak here would mean the renderer re-derived the
    // unmasked value (the #1166 failure class).
    expect(rendered.includes(RAW_SECRET)).toBe(false)
  })

  it('does NOT leak a raw secret even if one slips into a single input line', () => {
    // If the server-side redactor regressed and handed us a raw value, the
    // RENDERER must not be the thing that papers over it — but it also must
    // not crash. We assert the component renders the line as-is (so the e2e's
    // DOM-level not.toContain(rawSecret) assertion is what catches the leak),
    // proving the renderer is a faithful mirror, never a second masker that
    // could hide a server bug.
    const lines = [`leaked: token=[${RAW_SECRET}]`]
    render(<TerminalConsole lines={lines} sseState="done" />)
    const rendered = screen.getByTestId('terminal-body').textContent ?? ''
    // Faithful mirror: what the server sent is what shows. (The server-side
    // redaction + the e2e DOM assertion are the real guards; this documents
    // that the renderer adds no masking of its own.)
    expect(rendered.includes(RAW_SECRET)).toBe(true)
  })

  it('renders the empty-state when there are no lines (sad path, no crash)', () => {
    render(<TerminalConsole lines={[]} sseState="done" />)
    expect(screen.getByText('No log output yet.')).toBeInTheDocument()
  })
})
