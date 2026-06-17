import { useEffect, useRef, useState } from 'react'
import { useJobParameters } from '@/api/hooks'
import type { PipelineParameterDto } from '@/api/types'

/**
 * Shared "Run a pipeline, honoring its declared parameters" resolution.
 *
 * Both trigger entry points — the `/pipelines` list-row quick-trigger
 * ({@link QuickTriggerButton}) and the pipeline detail page — must lazily fetch
 * a pipeline's declared parameters on click and then branch: open a
 * set-parameters step if any are declared, else fall through to the caller's
 * no-params path.
 *
 * Before this hook each call site owned a private copy of that `clicked` state +
 * resolve effect. #779 upgraded only the detail page; the list-row trigger kept
 * firing parameterless builds with no way to set parameters (operator-reported,
 * fixed in #1208). Extracting the resolution here gives ONE implementation with
 * two callers, so the two can't drift again — the actual root cause, not just
 * the symptom.
 *
 * The caller supplies `onResolved`, invoked once with the declared parameters
 * after the lazy fetch settles. A missing/broken `/parameters` endpoint resolves
 * to an empty array, so failure degrades to the no-params path rather than
 * blocking the build.
 */
export function useParamAwareTrigger(
  jobId: number | undefined,
  onResolved: (params: PipelineParameterDto[]) => void,
) {
  const [clicked, setClicked] = useState(false)
  const query = useJobParameters(jobId, clicked)

  // Hold the latest callback in a ref so a fresh closure each render doesn't
  // re-fire the resolve effect (the effect keys off the query lifecycle only).
  const onResolvedRef = useRef(onResolved)
  onResolvedRef.current = onResolved

  useEffect(() => {
    if (!clicked) return
    if (query.isLoading) return
    setClicked(false)
    onResolvedRef.current(query.data ?? [])
  }, [clicked, query.isLoading, query.data])

  return {
    /** Call on the Run click — kicks off the lazy fetch + resolution. */
    start: () => setClicked(true),
    /** True between click and resolution (parameters still loading). */
    isResolving: clicked && query.isLoading,
    /** The declared parameters, once fetched — for the modal to render. */
    parameters: query.data ?? [],
  }
}
