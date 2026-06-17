/**
 * Adversarial tests for ArtifactsPanel v3-stack restyle (#559) + #849 signed-URL flow.
 *
 * Pinned invariants:
 *   1. The filter input narrows the row list by case-insensitive filename
 *      substring — exact-match regressions go silent and lose the artifact.
 *   2. Size formatting uses the canonical 1024-step ladder (B/KB/MB/GB) so
 *      "test-report.xml" (12 KiB) doesn't render as a misleading "12000 B".
 *   3. Each row's download anchor href is the HMAC-signed URL minted by
 *      /sign-download — NOT the bearer-gated /download path, which would 401
 *      under a browser-native anchor navigation (issue #849).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ArtifactsPanel } from '../components/ArtifactsPanel'
import * as hooks from '../api/hooks'
import type { ArtifactDto, ArtifactsPage } from '../api/types'

function art(
  id: number,
  name: string,
  sizeBytes: number,
  opts: Partial<ArtifactDto> = {},
): ArtifactDto {
  return {
    id,
    name,
    sizeBytes,
    sha256: 'a'.repeat(64),
    uploadedAt: '2026-05-20T10:00:00Z',
    downloadUrl: `/api/v1/artifacts/${id}/download`,
    ...opts,
  }
}

function mockArtifacts(items: ArtifactDto[]) {
  const page: ArtifactsPage = { items, total: items.length }
  vi.spyOn(hooks, 'useArtifacts').mockReturnValue({
    data: page,
    isLoading: false,
    isError: false,
    error: null,
  } as unknown as ReturnType<typeof hooks.useArtifacts>)
  // #849 — the panel now resolves each row's href via the async signer hook.
  // Stub it so the per-artifact id round-trips into the rendered href.
  vi.spyOn(hooks, 'resolveArtifactDownloadUrl').mockImplementation(
    async (id: number) => `http://api.test/api/v1/artifacts/${id}/download?token=v1.${id}.0.FAKE`,
  )
}

function renderPanel() {
  // Reset the module-level signed-URL cache between tests so a stale entry
  // from a prior test does not leak into this assertion.
  hooks.__clearSignedDownloadUrlCacheForTesting()
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <ArtifactsPanel buildId={1} />
    </QueryClientProvider>,
  )
}

describe('ArtifactsPanel v3 — restyle (#559) + signed download (#849)', () => {
  beforeEach(() => {})
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('filter input narrows rows by case-insensitive filename substring', () => {
    mockArtifacts([
      art(1, 'build.log', 1234),
      art(2, 'TEST-results.xml', 5678),
      art(3, 'coverage/jacoco.xml', 9999),
    ])
    renderPanel()
    // All 3 rows visible initially.
    expect(screen.getByTestId('artifact-row-1')).toBeInTheDocument()
    expect(screen.getByTestId('artifact-row-2')).toBeInTheDocument()
    expect(screen.getByTestId('artifact-row-3')).toBeInTheDocument()

    const input = screen.getByTestId('artifacts-filter')
    fireEvent.change(input, { target: { value: 'XML' } }) // uppercase → case-insensitive

    // Adversarial: "build.log" must be gone (no false-positive substrings).
    expect(screen.queryByTestId('artifact-row-1')).toBeNull()
    expect(screen.getByTestId('artifact-row-2')).toBeInTheDocument()
    expect(screen.getByTestId('artifact-row-3')).toBeInTheDocument()

    // Narrower filter: only one match.
    fireEvent.change(input, { target: { value: 'jacoco' } })
    expect(screen.queryByTestId('artifact-row-1')).toBeNull()
    expect(screen.queryByTestId('artifact-row-2')).toBeNull()
    expect(screen.getByTestId('artifact-row-3')).toBeInTheDocument()
  })

  it('formats sizes on the 1024-step ladder (B / KB / MB) — no misleading raw bytes', () => {
    mockArtifacts([
      art(10, 'tiny.txt', 512), // < 1 KiB → "B"
      art(11, 'mid.json', 2048), // 2 KiB → "2.0 KB"
      art(12, 'big.bin', 1024 * 1024 * 5), // 5 MiB → "5.0 MB"
    ])
    renderPanel()
    expect(screen.getByTestId('artifact-row-10').textContent).toContain('512 B')
    expect(screen.getByTestId('artifact-row-11').textContent).toContain('2.0 KB')
    expect(screen.getByTestId('artifact-row-12').textContent).toContain('5.0 MB')
    // Adversarial: raw byte count must NOT appear as a fallback.
    expect(screen.getByTestId('artifact-row-12').textContent).not.toContain('5242880')
  })

  it('download anchor uses the signed URL minted by /sign-download (#849)', async () => {
    mockArtifacts([art(42, 'report.html', 4096)])
    renderPanel()
    const link = screen.getByTestId('artifact-download-42') as HTMLAnchorElement
    expect(link.tagName).toBe('A')
    // The async signer round-trip resolves on the next tick; once it lands the href becomes
    // the signed URL. A bare /api/v1/artifacts/42/download would 401 from an anchor nav.
    await waitFor(() => {
      expect(link.getAttribute('href')).toBe(
        'http://api.test/api/v1/artifacts/42/download?token=v1.42.0.FAKE',
      )
    })
    expect(link.getAttribute('aria-label')).toBe('Download report.html')
    // Until the signer resolves, the anchor is aria-disabled (and the onClick handler
    // suppresses the navigation) so a fast click on a slow network never lands on '#'.
    // After the await above, it must be enabled.
    expect(link.getAttribute('aria-disabled')).toBe('false')
  })

  it('renders the v3 summary header with count + total size', () => {
    mockArtifacts([art(1, 'a.txt', 1024), art(2, 'b.bin', 1024)])
    renderPanel()
    const summary = screen.getByTestId('artifacts-summary')
    expect(summary.textContent).toMatch(/2/)
    expect(summary.textContent).toMatch(/artifacts/)
    expect(summary.textContent).toMatch(/2\.0 KB/) // total
  })

  it('shows the v3 empty state when no artifacts exist', () => {
    mockArtifacts([])
    renderPanel()
    const empty = screen.getByTestId('artifacts-empty')
    expect(empty.textContent).toBe('No artifacts archived for this build.')
  })

  it('renders a user-visible error state when the artifacts query fails (closes #740)', () => {
    // Adversarial: a backend 5xx or a deleted-build race must NOT crash the
    // panel. The hook surfaces isError; the panel must render a muted error
    // line instead of throwing during render.
    vi.spyOn(hooks, 'useArtifacts').mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('HTTP 500'),
    } as unknown as ReturnType<typeof hooks.useArtifacts>)

    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={qc}>
        <ArtifactsPanel buildId={1} />
      </QueryClientProvider>,
    )

    expect(screen.getByText(/failed to load artifacts/i)).toBeInTheDocument()
    // Empty-state placeholder must NOT also render — these are mutually exclusive.
    expect(screen.queryByTestId('artifacts-empty')).toBeNull()
  })
})
