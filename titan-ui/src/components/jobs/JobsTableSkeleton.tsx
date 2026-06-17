/**
 * Loading skeleton for the pipelines table (extracted from
 * `routes/pipelines/index.tsx` for issue #1070; carved out of `JobsTable.tsx`
 * to keep both modules under the file-size soft cap).
 *
 * Renders while {@code useJobs} is in flight — a 4-row shimmer matching the
 * real table's column layout so the page doesn't jump on settle.
 */
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/Table'
import { Skeleton } from '@/components/ui/Skeleton'

export function JobsSkeleton() {
  return (
    <div className="card">
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead style={{ width: 1 }}></TableHead>
            <TableHead style={{ width: 1 }}></TableHead>
            <TableHead>Name</TableHead>
            <TableHead>Enabled</TableHead>
            <TableHead>Last build</TableHead>
            <TableHead>Recent</TableHead>
            <TableHead>Duration</TableHead>
            <TableHead>Finished</TableHead>
            <TableHead></TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {Array.from({ length: 4 }).map((_, i) => (
            <TableRow key={i}>
              <TableCell>
                <Skeleton style={{ height: 18, width: 18, borderRadius: 4 }} />
              </TableCell>
              <TableCell>
                <Skeleton style={{ height: 18, width: 18, borderRadius: 4 }} />
              </TableCell>
              <TableCell>
                <Skeleton style={{ height: 12, width: '70%' }} />
              </TableCell>
              <TableCell>
                <Skeleton style={{ height: 18, width: 72, borderRadius: 999 }} />
              </TableCell>
              <TableCell>
                <Skeleton style={{ height: 18, width: 80, borderRadius: 999 }} />
              </TableCell>
              <TableCell>
                <Skeleton style={{ height: 22, width: 90, borderRadius: 3 }} />
              </TableCell>
              <TableCell>
                <Skeleton style={{ height: 12, width: 40 }} />
              </TableCell>
              <TableCell>
                <Skeleton style={{ height: 12, width: 70 }} />
              </TableCell>
              <TableCell>
                <Skeleton style={{ height: 12, width: 30 }} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
