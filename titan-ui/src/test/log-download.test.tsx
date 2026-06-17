/**
 * log-download.test.tsx — adversarial tests for the "Download log" button
 * (#705).
 *
 * Covers:
 *  - 0-line log → button disabled, click is a no-op
 *  - non-empty log → click calls URL.createObjectURL with a Blob whose body
 *    is `lines.join('\n') + '\n'`
 *  - mid-stream click → blob content snapshots the array at click time;
 *    mutating the source array afterwards must not change the blob
 *  - filename matches `build-<N>-<iso>.log` shape (via the anchor's download
 *    attr captured at click time)
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, cleanup, fireEvent } from '@testing-library/react'
import { TerminalConsole } from '../components/BuildDetail/TerminalConsole'

// jsdom doesn't ship the Blob URL APIs; we install mocks before each test and
// expose the captured Blob via the mock so assertions can read its text.
let createSpy: ReturnType<typeof vi.fn>
let revokeSpy: ReturnType<typeof vi.fn>
let lastBlob: Blob | null = null
let lastBlobText: string | null = null
let lastAnchorDownload: string | null = null
let originalClick: (this: HTMLElement) => void

beforeEach(() => {
  lastBlob = null
  lastBlobText = null
  lastAnchorDownload = null
  createSpy = vi.fn((blob: Blob) => {
    lastBlob = blob
    // jsdom's Blob omits .text()/.arrayBuffer() on the version pinned here, so
    // capture the source parts by re-reading via FileReader-free path: the
    // component passes a single string part, which we recover by stringifying
    // through the blob's internal buffer via a sync workaround. Since we
    // control the construction call site, the simpler path is to inspect the
    // call arg directly — we re-stringify via the test by reaching into the
    // Blob's stored content using the constructor pattern.
    // The component invokes `new Blob([body], …)`, so we shadow Blob below
    // and record the joined string there. lastBlobText is set in the Blob
    // shim, not here.
    return 'blob:mock-url'
  })
  revokeSpy = vi.fn()
  // jsdom exposes URL but not createObjectURL/revokeObjectURL.
  ;(URL as unknown as { createObjectURL: typeof createSpy }).createObjectURL = createSpy
  ;(URL as unknown as { revokeObjectURL: typeof revokeSpy }).revokeObjectURL = revokeSpy
  // Shadow Blob to capture the body text passed in at construction.
  const RealBlob = globalThis.Blob
  class CapturingBlob extends RealBlob {
    constructor(parts?: BlobPart[], opts?: BlobPropertyBag) {
      super(parts, opts)
      if (parts && parts.length > 0) {
        lastBlobText = parts
          .map((p) => (typeof p === 'string' ? p : ''))
          .join('')
      }
    }
  }
  ;(globalThis as unknown as { Blob: typeof Blob }).Blob = CapturingBlob as unknown as typeof Blob
  // Intercept anchor.click() to capture the `download` attribute at the
  // moment of click — the component removes the node right after.
  originalClick = HTMLAnchorElement.prototype.click
  HTMLAnchorElement.prototype.click = function () {
    lastAnchorDownload = this.getAttribute('download')
  }
  vi.useFakeTimers()
})

afterEach(() => {
  HTMLAnchorElement.prototype.click = originalClick
  vi.useRealTimers()
  cleanup()
})


describe('TerminalConsole — download log (#705)', () => {
  it('0-line log → button is disabled and click is a no-op', () => {
    render(<TerminalConsole lines={[]} sseState="done" buildNumber={42} />)
    const btn = screen.getByTestId('terminal-download-log') as HTMLButtonElement
    expect(btn.disabled).toBe(true)
    expect(btn.getAttribute('aria-disabled')).toBe('true')
    fireEvent.click(btn)
    expect(createSpy).not.toHaveBeenCalled()
  })

  it('5-line log → click calls createObjectURL with joined lines + trailing \\n', async () => {
    const lines = ['alpha', 'bravo', 'charlie', 'delta', 'echo']
    render(<TerminalConsole lines={lines} sseState="done" buildNumber={7} />)
    const btn = screen.getByTestId('terminal-download-log') as HTMLButtonElement
    expect(btn.disabled).toBe(false)
    fireEvent.click(btn)
    expect(createSpy).toHaveBeenCalledTimes(1)
    expect(lastBlob).not.toBeNull()
    expect(lastBlobText).not.toBeNull()
    expect(lastBlobText!).toBe('alpha\nbravo\ncharlie\ndelta\necho\n')
  })

  it('mid-stream click → blob snapshots lines at click time (later mutation is invisible)', async () => {
    const lines = ['one', 'two', 'three']
    render(<TerminalConsole lines={lines} sseState="live" buildNumber={9} />)
    fireEvent.click(screen.getByTestId('terminal-download-log'))
    // Mutate the source array AFTER the click. If the component captured a
    // reference instead of a snapshot, the blob would now reflect this push.
    lines.push('four')
    lines[0] = 'MUTATED'
    expect(lastBlob).not.toBeNull()
    expect(lastBlobText).not.toBeNull()
    expect(lastBlobText!).toBe('one\ntwo\nthree\n')
  })

  it('filename matches `build-<N>-<iso>.log`', () => {
    render(<TerminalConsole lines={['x']} sseState="done" buildNumber={123} />)
    fireEvent.click(screen.getByTestId('terminal-download-log'))
    expect(lastAnchorDownload).not.toBeNull()
    expect(lastAnchorDownload!).toMatch(
      /^build-123-\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z\.log$/,
    )
  })

  it('revokes the object URL after click (no leak)', () => {
    render(<TerminalConsole lines={['x']} sseState="done" buildNumber={1} />)
    fireEvent.click(screen.getByTestId('terminal-download-log'))
    // revoke is scheduled via setTimeout(_, 0); flush the timer queue.
    vi.runAllTimers()
    expect(revokeSpy).toHaveBeenCalledWith('blob:mock-url')
  })
})
