/**
 * useDebouncedValue — returns {@code value} after it has been stable for
 * {@code delayMs}. Lets a TanStack-Query {@code queryKey} include a search
 * string without firing a fetch on every keystroke.
 *
 * <p>Implementation is the textbook setTimeout/clearTimeout pair; the
 * cleanup function cancels the pending update when {@code value} changes
 * again inside the window. {@code delayMs = 0} short-circuits — useful in
 * tests that need synchronous propagation.
 */
import { useEffect, useState } from 'react'

export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState<T>(value)

  useEffect(() => {
    if (delayMs <= 0) {
      setDebounced(value)
      return
    }
    const handle = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(handle)
  }, [value, delayMs])

  return debounced
}
