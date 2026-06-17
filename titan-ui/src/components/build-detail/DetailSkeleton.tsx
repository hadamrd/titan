/**
 * DetailSkeleton — placeholder shown while /builds/$id is loading.
 * Extracted from `/builds/$buildId.tsx` (ticket #851).
 */
import { Skeleton } from '@/components/ui/Skeleton'

export function DetailSkeleton() {
  return (
    <div className="build-detail-v3">
      <div style={{ padding: 16 }}>
        <Skeleton style={{ height: 22, width: 200 }} />
      </div>
      <Skeleton style={{ height: 320, width: '100%', display: 'block' }} />
    </div>
  )
}
