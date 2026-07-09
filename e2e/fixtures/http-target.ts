/**
 * http-target — a spec-owned local HTTP target for worker-side `httpRequest`
 * steps (issue #119; pattern lifted from pulsar-node.ts / PR #98).
 *
 * Why: spec 32 (fixture-with-httpRequest) used to point the worker's
 * httpRequest step at https://httpbin.org — the last external-internet
 * dependency in the golden set. A 2026-07-09 smoke run lost build 1225 to an
 * httpbin 503 (`resultJson: "... returned status 503, expected 100:399"`).
 * This fixture replaces it with a plain Node http server the SPEC binds on
 * the host, serving a deterministic 200 JSON and recording every request —
 * the recording doubles as a hermeticity oracle (the step provably hit OUR
 * endpoint, not the internet).
 *
 * Reachability from the worker container: unlike titan-server, the
 * `titan-worker` service in rig/local/docker-compose.yml has NO
 * `extra_hosts: host.docker.internal:host-gateway` entry (issue #119's
 * premise that it does is wrong — only titan-server carries it), so
 * `host.docker.internal` does NOT resolve inside the worker. On a Linux/WSL2
 * rig the worker still reaches host-bound 0.0.0.0 listeners through the
 * compose network's gateway IP (verified empirically: `local_titan` gateway
 * 172.20.0.1 round-trips; host.docker.internal does not). So the resolution
 * order for the address we bake into the pipeline URL is:
 *
 *   1. TITAN_E2E_HOST_ADDR         — explicit override, always wins.
 *   2. `docker network inspect`    — the rig network's IPAM gateway
 *      (network name from TITAN_RIG_DOCKER_NETWORK, default `local_titan`).
 *   3. host.docker.internal        — last resort (works on Docker Desktop
 *      or if the worker service ever gains extra_hosts).
 *
 * Ownership + litter: the server is per-spec state, closed in the spec's
 * finally block. Nothing touches the rig's shared DB. The port is pinned by
 * the caller (spec 32 uses 18099, next to pulsar's 18098) and must match the
 * URL substituted into the fixture pipeline.
 */
import { spawnSync } from 'node:child_process'
import * as http from 'node:http'

/** One request the worker's httpRequest step delivered to the fake target. */
export interface RecordedHttpRequest {
  method: string
  path: string
  /** Raw request body as UTF-8 (the fixture posts `{"e2e":true}`). */
  body: string
  receivedAt: number
}

export interface FakeHttpTarget {
  port: number
  /** Every request received, in arrival order. */
  requests: RecordedHttpRequest[]
  close(): Promise<void>
}

/**
 * Resolve the address the WORKER container can dial to reach a host-bound
 * listener. See the module header for the three-step resolution order.
 */
export function workerReachableHostAddr(): string {
  const override = process.env.TITAN_E2E_HOST_ADDR
  if (override) return override

  const network = process.env.TITAN_RIG_DOCKER_NETWORK ?? 'local_titan'
  const r = spawnSync(
    'docker',
    ['network', 'inspect', network, '--format', '{{range .IPAM.Config}}{{.Gateway}}{{end}}'],
    { encoding: 'utf8' },
  )
  const gateway = r.status === 0 ? (r.stdout ?? '').trim() : ''
  if (gateway && /^[0-9a-fA-F.:]+$/.test(gateway)) return gateway

  return 'host.docker.internal'
}

/**
 * Start the fake HTTP target on `port` (0.0.0.0 — the worker dials in via the
 * docker network gateway, see workerReachableHostAddr). Every POST is
 * recorded verbatim and answered `200 {"ok":true,"echo":<body>}`, which lands
 * `"status":200` in the httpRequest flow-node's result_json — the same oracle
 * shape httpbin's 200 produced. Non-POST methods get 405 (the fixture only
 * POSTs; anything else reaching this server is a bug worth surfacing).
 */
export function startFakeHttpTarget(port: number): Promise<FakeHttpTarget> {
  const requests: RecordedHttpRequest[] = []

  const server = http.createServer((req, res) => {
    if (req.method !== 'POST') {
      res.writeHead(405).end()
      return
    }
    const chunks: Buffer[] = []
    req.on('data', (c: Buffer) => chunks.push(c))
    req.on('end', () => {
      const body = Buffer.concat(chunks).toString('utf8')
      requests.push({
        method: req.method ?? 'POST',
        path: req.url ?? '/',
        body,
        receivedAt: Date.now(),
      })
      const payload = JSON.stringify({ ok: true, echo: body })
      res
        .writeHead(200, {
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(payload),
        })
        .end(payload)
    })
  })

  return new Promise((resolve, reject) => {
    server.once('error', (err: NodeJS.ErrnoException) => {
      reject(
        new Error(
          err.code === 'EADDRINUSE'
            ? `fake http target port ${port} is already bound — is another spec run live, ` +
              `or does something else own :${port}? The fixture pipeline's TARGET_URL pins this port.`
            : `fake http target failed to start: ${err.message}`,
        ),
      )
    })
    server.listen(port, '0.0.0.0', () => {
      resolve({
        port,
        requests,
        close: () =>
          new Promise<void>((res2) => {
            server.close(() => res2())
            // Do not wait on keep-alive sockets — the worker's JDK client may hold one.
            server.closeAllConnections?.()
          }),
      })
    })
  })
}
