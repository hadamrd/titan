/**
 * ArtifactsPanel — v3-stack restyle (#559).
 *
 * Wire shape (titan-server PR #300):
 *   GET /api/v1/builds/{id}/artifacts?offset=0&limit=200
 *     → { items: ArtifactDto[], total }
 *
 * v3 layout: filter input + summary header on top, dense file rows below.
 * Each row: file icon | mono filename | size (right-aligned) | actions
 * (download; preview-text button for text/* content).
 *
 * Tokens used (src/styles/tokens.css):
 *   - var(--surface), var(--surface-2), var(--border) for table frame
 *   - var(--fg) / var(--fg-muted) / var(--fg-dim) for hierarchy
 *   - .btn / .btn-sm / .btn-ghost for actions
 *   - oklch(0.12 0 0) term-style block for the preview body
 */
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Download, FileText, FileArchive, FileCode, File as FileIcon, Eye } from 'lucide-react'
import { resolveArtifactDownloadUrl, useArtifacts } from '@/api/hooks'
import { Skeleton } from '@/components/ui/Skeleton'
import type { ArtifactDto } from '@/api/types'

interface Props {
  buildId: number
}

const PAGE_SIZE = 200
const PREVIEW_MAX_BYTES = 256 * 1024 // 256 KiB cap

function humanBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function totalSize(items: ArtifactDto[]): number {
  let s = 0
  for (const a of items) s += a.sizeBytes
  return s
}

function isTextLike(a: ArtifactDto): boolean {
  if (a.contentType && a.contentType.startsWith('text/')) return true
  return /\.(txt|log|json|xml|yaml|yml|md|csv|tsv|html|css|js|ts|tsx|jsx|sh|py|java|sql|conf|ini|toml|properties)$/i.test(
    a.name,
  )
}

function iconFor(a: ArtifactDto) {
  if (isTextLike(a)) return FileText
  if (/\.(zip|tar|gz|tgz|bz2|7z|rar|jar|war)$/i.test(a.name)) return FileArchive
  if (/\.(js|ts|tsx|jsx|json|xml|yaml|yml|html|css|java|py|sh|sql)$/i.test(a.name))
    return FileCode
  return FileIcon
}

export function ArtifactsPanel({ buildId }: Props) {
  const [filter, setFilter] = useState('')
  const [previewId, setPreviewId] = useState<number | null>(null)
  const [previewText, setPreviewText] = useState<string | null>(null)
  const [previewError, setPreviewError] = useState<string | null>(null)
  const [previewLoading, setPreviewLoading] = useState(false)

  const { data, isLoading, isError } = useArtifacts(buildId, 0, PAGE_SIZE)

  const items: ArtifactDto[] = data?.items ?? []
  const total = data?.total ?? 0

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase()
    if (!q) return items
    return items.filter((a) => a.name.toLowerCase().includes(q))
  }, [items, filter])

  const totalBytes = useMemo(() => totalSize(items), [items])

  async function loadPreview(a: ArtifactDto) {
    setPreviewId(a.id)
    setPreviewText(null)
    setPreviewError(null)
    setPreviewLoading(true)
    try {
      // #849 — the signed URL embeds an HMAC token so the fetch (and any future iframe
      // navigation) authenticates without a bearer header. The same URL is reused for Get
      // via the per-artifact cache in resolveArtifactDownloadUrl.
      const url = await resolveArtifactDownloadUrl(a.id)
      const res = await fetch(url)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const blob = await res.blob()
      const slice = blob.slice(0, PREVIEW_MAX_BYTES)
      const text = await slice.text()
      setPreviewText(text)
    } catch (err) {
      setPreviewError(err instanceof Error ? err.message : 'Preview failed.')
    } finally {
      setPreviewLoading(false)
    }
  }

  /**
   * Per-row signed download URL — minted lazily once the artifact rows are visible. Stored as a
   * Map<artifactId, url> so the Get anchor's {@code href} can be a real URL (browsers won't
   * follow an async function). Refreshed on filter / page change so a stale (5-min-old) URL is
   * never rendered.
   *
   * <p>Tradeoff: this pre-mints one signed URL per visible artifact, costing N small POSTs on
   * panel open. Acceptable for the v1 page size cap (200 rows). If that becomes a hot spot
   * we'll swap to mint-on-click via a button-shaped handler that opens window.location.
   */
  const [downloadUrls, setDownloadUrls] = useState<Map<number, string>>(new Map())

  const refreshDownloadUrls = useCallback(async (rows: ArtifactDto[]) => {
    const entries = await Promise.all(
      rows.map(async (a) => [a.id, await resolveArtifactDownloadUrl(a.id)] as const),
    )
    setDownloadUrls(new Map(entries))
  }, [])

  useEffect(() => {
    if (items.length === 0) return
    void refreshDownloadUrls(items)
  }, [items, refreshDownloadUrls])

  function closePreview() {
    setPreviewId(null)
    setPreviewText(null)
    setPreviewError(null)
  }

  if (isLoading) {
    return (
      <div className="tab-pane" style={{ padding: 12 }}>
        <Skeleton style={{ height: 32, width: 280, marginBottom: 10 }} />
        <div
          style={{
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--r-md)',
            overflow: 'hidden',
          }}
        >
          {Array.from({ length: 6 }).map((_, i) => (
            <div
              key={i}
              style={{
                display: 'grid',
                gridTemplateColumns: '24px 1fr 90px auto',
                gap: 12,
                alignItems: 'center',
                padding: '8px 14px',
                borderBottom: '1px solid var(--border)',
              }}
            >
              <Skeleton style={{ height: 14, width: 14 }} />
              <Skeleton style={{ height: 12, width: '70%' }} />
              <Skeleton style={{ height: 12, width: 60, justifySelf: 'end' }} />
              <Skeleton style={{ height: 22, width: 96 }} />
            </div>
          ))}
        </div>
      </div>
    )
  }

  if (isError) {
    return (
      <div className="empty" style={{ color: 'var(--fail)' }}>
        Failed to load artifacts.
      </div>
    )
  }

  if (items.length === 0) {
    return (
      <div className="empty" data-testid="artifacts-empty">
        No artifacts archived for this build.
      </div>
    )
  }

  return (
    <div className="tab-pane" style={{ padding: 12 }}>
      {/* Summary header */}
      <div
        data-testid="artifacts-summary"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 12,
          marginBottom: 10,
          fontFamily: 'var(--font-mono)',
          fontSize: 12,
          color: 'var(--fg-dim)',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        <span>
          <span style={{ color: 'var(--fg)' }}>{total}</span> artifacts
        </span>
        <span style={{ color: 'var(--fg-faint)' }}>·</span>
        <span>
          Total <span style={{ color: 'var(--fg)' }}>{humanBytes(totalBytes)}</span>
        </span>
      </div>

      {/* Filter input */}
      <div style={{ marginBottom: 10 }}>
        <input
          type="search"
          placeholder="Filter by filename…"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
          aria-label="Filter artifacts by filename"
          data-testid="artifacts-filter"
          style={{
            width: '100%',
            padding: '6px 10px',
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--r-sm)',
            color: 'var(--fg)',
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            outline: 'none',
          }}
        />
      </div>

      {/* Table */}
      {filtered.length === 0 ? (
        <div className="empty" style={{ padding: 32 }}>
          No artifacts match this filter.
        </div>
      ) : (
        <div
          role="table"
          aria-label="Artifacts"
          data-testid="artifacts-table"
          style={{
            background: 'var(--surface)',
            border: '1px solid var(--border)',
            borderRadius: 'var(--r-md)',
            overflow: 'hidden',
          }}
        >
          {filtered.map((a) => {
            const Icon = iconFor(a)
            const textPreviewable = isTextLike(a)
            // #849 — anchor href is the signed URL; falls back to '#' until /sign-download
            // resolves. The button is disabled while pending so the user can't navigate to
            // an unauthenticated stub.
            const downloadHref = downloadUrls.get(a.id) ?? '#'
            const downloadReady = downloadUrls.has(a.id)
            return (
              <div key={a.id} data-testid={`artifact-row-${a.id}`}>
                <div
                  role="row"
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '24px 1fr 90px auto',
                    gap: 12,
                    alignItems: 'center',
                    padding: '6px 14px',
                    minHeight: 32,
                    borderBottom: '1px solid var(--border)',
                  }}
                >
                  <Icon
                    size={14}
                    color="var(--fg-dim)"
                    aria-hidden
                    style={{ flexShrink: 0 }}
                  />
                  <span
                    style={{
                      fontFamily: 'var(--font-mono)',
                      fontSize: 12,
                      color: 'var(--fg)',
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                    title={a.name}
                  >
                    {a.name}
                  </span>
                  <span
                    style={{
                      fontFamily: 'var(--font-mono)',
                      fontSize: 12,
                      color: 'var(--fg-muted)',
                      textAlign: 'right',
                      fontVariantNumeric: 'tabular-nums',
                    }}
                  >
                    {humanBytes(a.sizeBytes)}
                  </span>
                  <span style={{ display: 'inline-flex', gap: 4 }}>
                    {textPreviewable && (
                      <button
                        type="button"
                        className="btn btn-sm btn-ghost"
                        data-testid={`artifact-preview-${a.id}`}
                        onClick={() => loadPreview(a)}
                        aria-label={`Preview ${a.name}`}
                      >
                        <Eye size={12} aria-hidden /> Preview
                      </button>
                    )}
                    <a
                      href={downloadHref}
                      className="btn btn-sm btn-ghost"
                      data-testid={`artifact-download-${a.id}`}
                      aria-label={`Download ${a.name}`}
                      aria-disabled={!downloadReady}
                      onClick={(e) => {
                        // Defensive: if the signed URL hasn't arrived yet (network slow),
                        // block the navigation rather than landing on '#'.
                        if (!downloadReady) e.preventDefault()
                      }}
                    >
                      <Download size={12} aria-hidden /> Get
                    </a>
                  </span>
                </div>
                {previewId === a.id && (
                  <PreviewBlock
                    name={a.name}
                    loading={previewLoading}
                    text={previewText}
                    error={previewError}
                    onClose={closePreview}
                  />
                )}
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

function PreviewBlock({
  name,
  loading,
  text,
  error,
  onClose,
}: {
  name: string
  loading: boolean
  text: string | null
  error: string | null
  onClose: () => void
}) {
  return (
    <div
      data-testid="artifact-preview-block"
      style={{
        background: 'oklch(0.12 0 0)',
        color: 'oklch(0.88 0 0)',
        borderLeft: '2px solid var(--accent)',
        borderBottom: '1px solid var(--border)',
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '4px 12px',
          fontFamily: 'var(--font-mono)',
          fontSize: 11,
          color: 'var(--fg-dim)',
          borderBottom: '1px solid var(--border)',
        }}
      >
        <span>{name}</span>
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          onClick={onClose}
          aria-label="Close preview"
        >
          Close
        </button>
      </div>
      {loading && (
        <div style={{ padding: 12, color: 'var(--fg-dim)', fontSize: 12 }}>Loading…</div>
      )}
      {error && (
        <div style={{ padding: 12, color: 'var(--fail)', fontSize: 12 }}>{error}</div>
      )}
      {!loading && !error && text !== null && (
        <pre
          style={{
            margin: 0,
            padding: '10px 14px',
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-word',
            maxHeight: 360,
            overflow: 'auto',
          }}
        >
          {text}
        </pre>
      )}
    </div>
  )
}
