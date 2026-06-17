/**
 * Adversarial tests for the /builds/$id "YAML" tab (issue #533).
 *
 * SREs debugging a failure need to see exactly what YAML the worker actually
 * ran — the job's pipelineScript may have drifted since. The PipelineYamlPanel
 * is the surface that exposes the as-built YAML; these tests cover the three
 * failure modes that matter:
 *
 *   1. Happy path  — YAML present → mono block renders the exact text.
 *   2. Null state  — backend hasn't captured YAML → muted legacy message AND
 *                    the GH issue link, NOT a blank pane and NOT a crash.
 *   3. Copy button — writes the EXACT YAML (no trailing whitespace munging,
 *                    no HTML escaping) via navigator.clipboard.writeText.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { PipelineYamlPanel } from '../routes/builds/$buildId'

const SAMPLE_YAML = 'stages:\n  - stage: x'

describe('PipelineYamlPanel — /builds/$id YAML tab (issue #533)', () => {
  let writeTextSpy: ReturnType<typeof vi.fn>

  beforeEach(() => {
    writeTextSpy = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: writeTextSpy },
      configurable: true,
      writable: true,
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('renders the as-built YAML verbatim when pipelineScript is present', () => {
    render(<PipelineYamlPanel pipelineScript={SAMPLE_YAML} />)

    const body = screen.getByTestId('yaml-body')
    // textContent preserves newlines inside <pre>; the YAML must round-trip.
    expect(body.textContent).toBe(SAMPLE_YAML)
    // Copy affordance is visible.
    expect(screen.getByTestId('yaml-copy-button')).toBeInTheDocument()
    // Empty state must NOT render in parallel — would confuse SREs.
    expect(screen.queryByTestId('yaml-empty')).toBeNull()
  })

  it('renders the muted "not captured (legacy)" message + GH issue link when pipelineScript is null', () => {
    render(<PipelineYamlPanel pipelineScript={null} />)

    const empty = screen.getByTestId('yaml-empty')
    expect(empty.textContent?.toLowerCase()).toContain('not captured')
    expect(empty.textContent?.toLowerCase()).toContain('legacy')
    // The GH issue must be linked so the SRE knows this isn't a UI bug.
    const link = empty.querySelector('a[href*="issues/533"]')
    expect(link).not.toBeNull()
    // No copy button in the null state — nothing to copy.
    expect(screen.queryByTestId('yaml-copy-button')).toBeNull()
    // No YAML body either.
    expect(screen.queryByTestId('yaml-body')).toBeNull()
  })

  it('copy button calls navigator.clipboard.writeText with the EXACT YAML payload', () => {
    render(<PipelineYamlPanel pipelineScript={SAMPLE_YAML} />)

    const btn = screen.getByTestId('yaml-copy-button')
    fireEvent.click(btn)

    expect(writeTextSpy).toHaveBeenCalledTimes(1)
    // No trim, no escape, no normalization — what the worker saw is what hits
    // the clipboard.
    expect(writeTextSpy).toHaveBeenCalledWith(SAMPLE_YAML)
  })

  it('renders highlighted token classes for known YAML constructs (issue #655)', () => {
    // Adversarial: a plain <pre> regression would silently revert to one
    // text node with no .hljs-* classes. We assert that a known key
    // ("stages:") survives tokenization as an .hljs-attr span and that the
    // root <code> carries the .hljs class so the oklch theme applies.
    render(<PipelineYamlPanel pipelineScript={SAMPLE_YAML} />)

    const body = screen.getByTestId('yaml-body')
    const code = body.querySelector('code.hljs')
    expect(code).not.toBeNull()
    // The YAML key tokens must be wrapped with the hljs-attr class so the
    // syntax theme can colour them. highlight.js emits "stages" inside an
    // .hljs-attr span (the trailing ":" stays as text).
    const attrSpans = body.querySelectorAll('.hljs-attr')
    expect(attrSpans.length).toBeGreaterThan(0)
    const attrText = Array.from(attrSpans)
      .map((s) => s.textContent)
      .join(' ')
    expect(attrText).toMatch(/stages/)
    // Bullets ("- ") must also be tokenised — this is the second YAML
    // construct in the sample, and proves the parser actually ran.
    expect(body.querySelector('.hljs-bullet')).not.toBeNull()
    // Verbatim round-trip preserved — text content must still match the
    // raw YAML, irrespective of the wrapping span tree.
    expect(body.textContent).toBe(SAMPLE_YAML)
  })

  it('copy button is inert (no clipboard call, no crash) when pipelineScript is null', () => {
    render(<PipelineYamlPanel pipelineScript={null} />)

    // No copy button in the null state, but if some future change reintroduces
    // it the spy must remain unhit.
    expect(screen.queryByTestId('yaml-copy-button')).toBeNull()
    expect(writeTextSpy).not.toHaveBeenCalled()
  })
})
