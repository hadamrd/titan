/**
 * titan-api-v3 — REST client for the v3 Titan (Quarkus) rig.
 *
 * Distinct from the legacy controller-bound `titan-api.ts` (which posts Groovy to
 * `/scriptText` and polls `/job/<name>/...` URLs and is kept for
 * the legacy `scenarios.spec.ts` until Phase 3 deletes titan-plugin).
 *
 * All calls go through the nginx-proxied UI base URL (default
 * `http://localhost:5180/api/v1/...`) so CSP/CORS exactly mirrors what the
 * SPA experiences.
 */
const DEFAULT_BASE_URL = 'http://localhost:5180'

export interface GateDto {
  nodeId: string
  name: string
  approvers: string[]
  status: string
  awaitingSince?: string
}

export interface BuildDto {
  id: number
  jobId: number
  buildNumber: number
  status: string
}

export interface FlowNodeDto {
  nodeId: string
  status: string
  nodeType?: string
  displayName?: string
}

export class TitanApiV3 {
  private readonly baseUrl: string
  private readonly token: string

  constructor(token: string, baseUrl: string = process.env.TITAN_UI_URL ?? DEFAULT_BASE_URL) {
    this.token = token
    this.baseUrl = baseUrl.replace(/\/$/, '')
  }

  private headers(extra?: Record<string, string>): Record<string, string> {
    return {
      Authorization: `Bearer ${this.token}`,
      Accept: 'application/json',
      ...(extra ?? {}),
    }
  }

  async getBuild(buildId: number): Promise<BuildDto> {
    const res = await fetch(`${this.baseUrl}/api/v1/builds/${buildId}`, {
      headers: this.headers(),
    })
    if (!res.ok) throw new Error(`getBuild ${buildId} failed: ${res.status}`)
    return (await res.json()) as BuildDto
  }

  async listPendingGates(buildId: number): Promise<GateDto[]> {
    const res = await fetch(`${this.baseUrl}/api/v1/builds/${buildId}/gates`, {
      headers: this.headers(),
    })
    if (!res.ok) throw new Error(`listPendingGates ${buildId} failed: ${res.status}`)
    return (await res.json()) as GateDto[]
  }

  async approveGate(buildId: number, nodeId: string, reason?: string): Promise<void> {
    const res = await fetch(
      `${this.baseUrl}/api/v1/builds/${buildId}/gates/${encodeURIComponent(nodeId)}/approve`,
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(reason ? { reason } : {}),
      },
    )
    if (!res.ok) {
      const text = await res.text()
      throw new Error(`approveGate ${buildId}/${nodeId} failed: ${res.status} ${text}`)
    }
  }

  async rejectGate(buildId: number, nodeId: string, reason?: string): Promise<void> {
    const res = await fetch(
      `${this.baseUrl}/api/v1/builds/${buildId}/gates/${encodeURIComponent(nodeId)}/reject`,
      {
        method: 'POST',
        headers: this.headers({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(reason ? { reason } : {}),
      },
    )
    if (!res.ok) {
      const text = await res.text()
      throw new Error(`rejectGate ${buildId}/${nodeId} failed: ${res.status} ${text}`)
    }
  }

  async getFlowNodes(buildId: number): Promise<FlowNodeDto[]> {
    const res = await fetch(`${this.baseUrl}/api/v1/builds/${buildId}/nodes`, {
      headers: this.headers(),
    })
    if (!res.ok) throw new Error(`getFlowNodes ${buildId} failed: ${res.status}`)
    return (await res.json()) as FlowNodeDto[]
  }
}
