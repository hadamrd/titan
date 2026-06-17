/**
 * Legacy `/jobs/$jobId` route — redirects to `/pipelines/$pipelineId`
 * (design 66 vocabulary). The id literal is forwarded verbatim — it is the
 * same DB row, just renamed in the URL surface.
 */
import { createFileRoute, redirect } from '@tanstack/react-router'

export const Route = createFileRoute('/jobs/$jobId')({
  beforeLoad: ({ params }) => {
    throw redirect({
      to: '/pipelines/$pipelineId',
      params: { pipelineId: params.jobId },
      replace: true,
    })
  },
})
