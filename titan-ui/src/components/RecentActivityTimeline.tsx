/**
 * RecentActivityTimeline — Home-page "what's been happening" feed (closes #710).
 *
 * <p>Vertical event list synthesised from the build stream the caller passes in.
 * Each terminal build emits one "<verb>" event; each currently-running build
 * emits a "started" event. Sorted newest-first by the event's effective
 * timestamp ({@code finishedAt} for terminal, {@code startedAt} for running).
 *
 * <p>Workers: the {@code workers} prop is accepted for forward-compat with
 * the {@code /api/v1/workers} current-state surface; it is not rendered. Worker
 * lifecycle (join/leave) is now driven by the {@code events} prop — wired in
 * #714 from {@code GET /api/v1/agents/events} via {@code useAgentEvents}. The
 * events stream interleaves with the build stream by timestamp (newest first).
 *
 * <p>Click on a row routes to {@code /builds/$buildId}. Empty input renders the
 * muted "No recent activity yet" placeholder — the SRE landing on Home with
 * a fresh controller sees a calm explainer, not a blank panel.
 */
import { Link } from '@tanstack/react-router'
import type { AgentEventDto, BuildDto, WorkerDto } from '@/api/types'
import { isTerminal } from '@/api/types'
import { StatusDot, type StatusDotVariant } from '@/components/ui/StatusDot'

/** Synthesised event for the timeline — discriminated on {@code kind}. */
type TimelineEvent =
  | {
      kind: 'build-started'
      id: string
      buildId: number
      buildNumber: number
      jobName: string
      ts: string
    }
  | {
      kind: 'build-finished'
      id: string
      buildId: number
      buildNumber: number
      jobName: string
      status: string
      durationMs: number | null
      ts: string
    }
  | {
      kind: 'agent-joined'
      id: string
      agentId: string
      agentName: string
      ts: string
    }
  | {
      kind: 'agent-left'
      id: string
      agentId: string
      agentName: string
      ts: string
    }

export interface RecentActivityTimelineProps {
  builds: readonly BuildDto[]
  /** Worker lifecycle events (closes #714) — interleaved with builds by ts. */
  events?: readonly AgentEventDto[]
  /** Reserved (current-state surface) — see file header. */
  workers?: readonly WorkerDto[]
  /** Hard cap on rendered events (default 20). */
  limit?: number
}

export function RecentActivityTimeline({
  builds,
  events: agentEvents,
  limit = 20,
}: RecentActivityTimelineProps) {
  const events = mergeTimelineEvents(builds, agentEvents ?? []).slice(0, limit)

  return (
    <div
      className="card"
      data-testid="recent-activity-timeline"
      style={{ marginTop: 14 }}
    >
      <div
        className="card-header"
        style={{
          display: 'flex',
          alignItems: 'baseline',
          gap: 10,
          padding: '12px 14px',
          borderBottom: '1px solid var(--border)',
        }}
      >
        <h3 className="card-title" style={{ margin: 0 }}>
          Recent activity
        </h3>
        <span className="badge" style={{ marginLeft: 'auto' }}>
          last {events.length || limit}
        </span>
      </div>

      {events.length === 0 ? (
        <div
          className="empty"
          style={{ padding: 18 }}
          data-testid="recent-activity-timeline-empty"
        >
          <p style={{ fontSize: 12, color: 'var(--fg-dim)', margin: 0 }}>
            No recent activity yet.
          </p>
        </div>
      ) : (
        <ul
          style={{
            listStyle: 'none',
            margin: 0,
            padding: 0,
          }}
        >
          {events.map((evt) => (
            <li key={evt.id}>
              <TimelineRow event={evt} />
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// ── Row ─────────────────────────────────────────────────────────────────────

function TimelineRow({ event }: { event: TimelineEvent }) {
  // Agent join/leave rows render as a plain row (no Link target — there's no
  // /agents/<id> route yet). Build rows keep the click-to-build deep link.
  if (event.kind === 'agent-joined' || event.kind === 'agent-left') {
    const verb = event.kind === 'agent-joined' ? 'joined' : 'left'
    const variant: StatusDotVariant =
      event.kind === 'agent-joined' ? 'success' : 'cancelled'
    return (
      <div
        data-testid={`timeline-row-${event.id}`}
        style={{
          display: 'grid',
          gridTemplateColumns: '16px 1fr auto',
          alignItems: 'center',
          gap: 10,
          padding: '10px 14px',
          borderBottom: '1px solid var(--border)',
          color: 'inherit',
        }}
      >
        <StatusDot variant={variant} />
        <span
          style={{
            fontSize: 13,
            color: 'var(--fg)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {event.agentName} {verb}
        </span>
        <span
          className="tabnum"
          style={{
            fontFamily: 'var(--font-mono)',
            fontSize: 11,
            color: 'var(--fg-faint)',
            textAlign: 'right',
          }}
        >
          {formatRelative(event.ts)}
        </span>
      </div>
    )
  }

  const variant: StatusDotVariant =
    event.kind === 'build-started'
      ? 'running'
      : statusDotVariant(event.status)

  const label =
    event.kind === 'build-started'
      ? `${event.jobName} #${event.buildNumber} started`
      : `${event.jobName} #${event.buildNumber} ${statusVerb(event.status)} in ${formatDurationOrDash(event.durationMs)}`

  return (
    <Link
      to="/builds/$buildId"
      params={{ buildId: String(event.buildId) }}
      data-testid={`timeline-row-${event.id}`}
      style={{
        display: 'grid',
        gridTemplateColumns: '16px 1fr auto',
        alignItems: 'center',
        gap: 10,
        padding: '10px 14px',
        borderBottom: '1px solid var(--border)',
        textDecoration: 'none',
        color: 'inherit',
      }}
    >
      <StatusDot variant={variant} />
      <span
        style={{
          fontSize: 13,
          color: 'var(--fg)',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {label}
      </span>
      <span
        className="tabnum"
        style={{
          fontFamily: 'var(--font-mono)',
          fontSize: 11,
          color: 'var(--fg-faint)',
          textAlign: 'right',
        }}
      >
        {formatRelative(event.ts)}
      </span>
    </Link>
  )
}

// ── Event synthesis ─────────────────────────────────────────────────────────

/**
 * Turn the raw build stream into timeline events. A terminal build yields one
 * "finished" event; a non-terminal build with a {@code startedAt} yields a
 * "started" event. Builds with no useful timestamp at all are dropped — they
 * can't be ordered and they read as noise.
 */
export function buildEventsFromBuilds(
  builds: readonly BuildDto[],
): TimelineEvent[] {
  const out: TimelineEvent[] = []
  for (const b of builds) {
    const jobName = `job #${b.jobId}`
    if (isTerminal(b.status) && b.finishedAt) {
      out.push({
        kind: 'build-finished',
        id: `b-${b.id}-fin`,
        buildId: b.id,
        buildNumber: b.buildNumber,
        jobName,
        status: b.status,
        durationMs: b.durationMs,
        ts: b.finishedAt,
      })
    } else if (b.startedAt) {
      out.push({
        kind: 'build-started',
        id: `b-${b.id}-start`,
        buildId: b.id,
        buildNumber: b.buildNumber,
        jobName,
        ts: b.startedAt,
      })
    }
  }
  // Newest first; stable on equal ts via id descending so test ordering is
  // deterministic when fixtures share the same second.
  out.sort(compareTimelineEvents)
  return out
}

/**
 * Turn the raw agent-event stream into timeline events. Each {@code JOINED} or
 * {@code LEFT} event yields one row; the forward-compat {@code HEARTBEAT_LOST}
 * type is currently rendered as {@code agent-left} (no reaper emits it yet —
 * see {@link AgentEventDto}).
 */
export function buildEventsFromAgentEvents(
  events: readonly AgentEventDto[],
): TimelineEvent[] {
  const out: TimelineEvent[] = []
  for (const e of events) {
    if (e.type === 'JOINED') {
      out.push({
        kind: 'agent-joined',
        id: `a-${e.id}-join`,
        agentId: e.agentId,
        agentName: e.agentName,
        ts: e.occurredAt,
      })
    } else {
      out.push({
        kind: 'agent-left',
        id: `a-${e.id}-left`,
        agentId: e.agentId,
        agentName: e.agentName,
        ts: e.occurredAt,
      })
    }
  }
  return out
}

/**
 * Merge build-derived and agent-derived events into one newest-first stream.
 * Equal-timestamp rows order deterministically by row id so test snapshots and
 * React keys stay stable.
 */
export function mergeTimelineEvents(
  builds: readonly BuildDto[],
  agentEvents: readonly AgentEventDto[],
): TimelineEvent[] {
  const merged: TimelineEvent[] = [
    ...buildEventsFromBuilds(builds),
    ...buildEventsFromAgentEvents(agentEvents),
  ]
  merged.sort(compareTimelineEvents)
  return merged
}

/** Comparator: newest-first by ts, tiebreak by id descending for determinism. */
function compareTimelineEvents(a: TimelineEvent, b: TimelineEvent): number {
  const ta = Date.parse(a.ts)
  const tb = Date.parse(b.ts)
  if (tb !== ta) return tb - ta
  // String id desc — lexicographic but stable; build & agent ids never collide
  // (prefixed 'b-' vs 'a-').
  return b.id.localeCompare(a.id)
}

// ── Formatters ──────────────────────────────────────────────────────────────

/** Pretty verb for terminal build status. */
function statusVerb(status: string): string {
  switch (status) {
    case 'SUCCESS':
      return 'succeeded'
    case 'FAILED':
    case 'FAILURE':
      return 'failed'
    case 'ABORTED':
    case 'CANCELLED':
      return 'cancelled'
    case 'UNSTABLE':
      return 'completed unstable'
    default:
      return status.toLowerCase()
  }
}

function statusDotVariant(status: string): StatusDotVariant {
  switch (status) {
    case 'SUCCESS':
      return 'success'
    case 'FAILED':
    case 'FAILURE':
      return 'fail'
    case 'ABORTED':
    case 'CANCELLED':
      return 'cancelled'
    case 'UNSTABLE':
      return 'warn'
    case 'RUNNING':
      return 'running'
    default:
      return 'queued'
  }
}

/**
 * Coarse relative-time label — seconds / minutes / hours / "yesterday" / "Nd ago".
 * Future timestamps clamp to "just now" (clock-skew defensive).
 */
export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—'
  const ts = Date.parse(iso)
  if (!Number.isFinite(ts)) return '—'
  const delta = Date.now() - ts
  if (delta < 0) return 'just now'
  const s = Math.floor(delta / 1000)
  if (s < 5) return 'just now'
  if (s < 60) return `${s}s ago`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24)
  if (d === 1) return 'yesterday'
  return `${d}d ago`
}

/**
 * Build-duration formatter that — unlike the shared {@code formatDuration} in
 * {@code lib/format} — renders {@code 0s} (not an em-dash) when the upstream
 * row carries a {@code null} duration_ms. The timeline phrasing "finished in
 * <dur>" reads badly with an em-dash; "finished in 0s" is the documented
 * v1 behaviour for builds where the engine didn't persist a duration (issue
 * #710 brief).
 */
function formatDurationOrDash(ms: number | null | undefined): string {
  if (ms == null || !Number.isFinite(ms) || ms < 0) return '0s'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}
