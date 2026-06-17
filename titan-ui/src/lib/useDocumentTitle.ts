import { useEffect } from 'react'

/**
 * Sets `document.title` to `<segment> — Titan` while the calling component
 * is mounted, and restores the previous value on unmount.
 *
 * Brand-polish (#47) regression guard: every route is expected to set its
 * own title via this hook so the browser tab + history reflect the route.
 * The vitest smoke (`Queue route /queue > sets document.title…`) covers
 * the contract.
 */
export function useDocumentTitle(segment: string | null | undefined): void {
  useEffect(() => {
    const previous = document.title
    const next = segment ? `${segment} — Titan` : 'Titan'
    document.title = next
    return () => {
      document.title = previous
    }
  }, [segment])
}
