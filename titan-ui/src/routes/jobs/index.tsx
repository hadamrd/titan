/**
 * Legacy `/jobs` route — redirects to `/pipelines` (design 66 vocabulary).
 *
 * The product surface was renamed: a "Job" is now called a "Pipeline" — the
 * discovered YAML file IS the pipeline. The API layer (`/api/v1/jobs`,
 * `useJobs`, `JobDto`) keeps its backend nomenclature; only the UI vocabulary
 * changed. Kept as a redirect so bookmarks survive.
 */
import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/jobs/')({
  beforeLoad: () => {
    throw redirect({ to: '/pipelines', replace: true })
  },
})
