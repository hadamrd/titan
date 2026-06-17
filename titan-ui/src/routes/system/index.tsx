/**
 * System — engine health dashboard (closes #676).
 *
 * Four cards: Version | DB | Queue Depth | Workers Online. One round-trip
 * via {@link useSystemInfo} (GET /api/v1/system/info, 10 s polling). The
 * DB tile is the discriminator-driven status card: green dot for UP, red
 * for DOWN — both via existing oklch tokens (no raw colors).
 *
 * Adversarial contract: a {@code DOWN} payload renders the red indicator
 * inline; we never block on the failed probe because the endpoint returns
 * 200 with {@code dbStatus: 'DOWN'} rather than 500. See {@code
 * SystemInfoApiIT.degradedDb_returnsDbStatusDown_withoutCrashing}.
 */
import { createFileRoute } from '@tanstack/react-router'
import { useSystemInfo } from '@/api/hooks'
import { ApiError, type SystemInfoDto } from '@/api/types'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { Skeleton } from '@/components/ui/Skeleton'
import { StatusDot } from '@/components/ui/StatusDot'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

export const Route = createFileRoute('/system/')({
  component: SystemPage,
})

function SystemPage() {
  useDocumentTitle('System')
  const { data, isLoading, error } = useSystemInfo()

  return (
    <PageContainer width="default">
      <PageHeader
        title="System"
        description="Engine health — version, database, queue depth, and worker availability."
      />

      {error ? (
        <div
          className="card empty"
          style={{ padding: 24 }}
          data-testid="system-error"
        >
          <p style={{ fontSize: 13, color: 'var(--fail)' }}>
            Failed to load engine health
          </p>
          <p style={{ fontSize: 12, color: 'var(--fg-dim)', marginTop: 4 }}>
            {error instanceof ApiError
              ? (error.problem.detail ?? error.problem.title)
              : String(error)}
          </p>
        </div>
      ) : (
        <div className="metric-grid" data-testid="system-cards">
          <VersionTile info={data} loading={isLoading} />
          <DbTile info={data} loading={isLoading} />
          <CounterTile
            label="Queue depth"
            value={isLoading || !data ? null : String(data.queueDepth)}
            testid="system-card-queue"
          />
          <CounterTile
            label="Workers online"
            value={isLoading || !data ? null : String(data.workersOnline)}
            testid="system-card-workers"
          />
        </div>
      )}

      {data && (
        <p
          data-testid="system-server-time"
          style={{
            marginTop: 16,
            fontSize: 11,
            color: 'var(--fg-faint)',
            fontFamily: 'var(--font-mono)',
          }}
        >
          Server time: {data.serverTime}
        </p>
      )}
    </PageContainer>
  )
}

function VersionTile({
  info,
  loading,
}: {
  info: SystemInfoDto | undefined
  loading: boolean
}) {
  return (
    <div className="metric" data-testid="system-card-version">
      <div className="metric-label">Version</div>
      <div className="metric-value">
        {loading || !info ? <Skeleton style={{ height: 24, width: 96 }} /> : info.version}
      </div>
      {info && (
        <div
          style={{
            marginTop: 4,
            fontSize: 11,
            color: 'var(--fg-dim)',
            fontFamily: 'var(--font-mono)',
          }}
        >
          {info.buildSha}
        </div>
      )}
    </div>
  )
}

function DbTile({
  info,
  loading,
}: {
  info: SystemInfoDto | undefined
  loading: boolean
}) {
  // Closed two-value discriminator → exhaustive branch. UP gets the
  // success dot; DOWN gets the fail dot. Both pull oklch values from
  // tokens.css — no raw hex literals.
  const up = info?.dbStatus === 'UP'
  const variant = up ? 'success' : 'fail'
  const label = info ? info.dbStatus : '—'

  return (
    <div className="metric" data-testid="system-card-db">
      <div className="metric-label">Database</div>
      <div
        className="metric-value"
        style={{ display: 'inline-flex', alignItems: 'center', gap: 10 }}
      >
        {loading || !info ? (
          <Skeleton style={{ height: 24, width: 64 }} />
        ) : (
          <>
            <StatusDot
              variant={variant}
              aria-label={`db ${label.toLowerCase()}`}
            />
            <span data-testid="system-db-status">{label}</span>
          </>
        )}
      </div>
    </div>
  )
}

function CounterTile({
  label,
  value,
  testid,
}: {
  label: string
  value: string | null
  testid: string
}) {
  return (
    <div className="metric" data-testid={testid}>
      <div className="metric-label">{label}</div>
      <div className="metric-value">
        {value === null ? <Skeleton style={{ height: 24, width: 80 }} /> : value}
      </div>
    </div>
  )
}
