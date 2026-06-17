/**
 * BuildsPagination — count footer + page controls for /builds.
 *
 * Controlled component:
 *   - {@code page} (0-indexed) + {@code pageSize} → derives the last page
 *     from {@code total}.
 *   - {@code onPageChange(nextPage)} fires on prev/next click.
 *   - "Previous" is disabled on page 0; "Next" is disabled on the last page
 *     (i.e. when there are no more rows to fetch).
 *   - When the entire result set fits in one page, the prev/next pair is
 *     omitted entirely so the footer collapses to the "X of Y shown" line
 *     that the existing tests anchor on (`builds-count-footer`).
 *
 * Extracted from `routes/builds/index.tsx` per #1071. Mirrors the
 * decomposition pattern landed in #851.
 */

interface BuildsPaginationProps {
  /** Number of rows currently visible (post-client-filter). */
  shown: number
  /** Server-side total — used both for the footer copy and for last-page math. */
  total: number
  /** 0-indexed current page. */
  page: number
  /** Page size (limit). */
  pageSize: number
  onPageChange: (nextPage: number) => void
}

export function BuildsPagination({
  shown,
  total,
  page,
  pageSize,
  onPageChange,
}: BuildsPaginationProps) {
  // Last-page index: ceil(total / pageSize) - 1, clamped to >= 0 so an empty
  // result set doesn't put "last page" at -1.
  const lastPage = Math.max(0, Math.ceil(total / pageSize) - 1)
  const showControls = total > pageSize
  const prevDisabled = page <= 0
  const nextDisabled = page >= lastPage

  return (
    <div className="builds-pagination" data-testid="builds-pagination">
      <p
        className="mono dim"
        style={{ fontSize: 11, color: 'var(--fg-dim)', marginTop: 14 }}
        data-testid="builds-count-footer"
      >
        {shown} of {total} shown
      </p>
      {showControls && (
        <div className="builds-pagination-controls">
          <button
            type="button"
            data-testid="builds-page-prev"
            disabled={prevDisabled}
            onClick={() => onPageChange(page - 1)}
            aria-label="Previous page"
          >
            Previous
          </button>
          <span data-testid="builds-page-indicator" className="mono dim">
            Page {page + 1} of {lastPage + 1}
          </span>
          <button
            type="button"
            data-testid="builds-page-next"
            disabled={nextDisabled}
            onClick={() => onPageChange(page + 1)}
            aria-label="Next page"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
