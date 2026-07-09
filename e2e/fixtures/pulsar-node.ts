/**
 * pulsar-node — a spec-owned fake Pulsar node for the Layer-1 golden spec
 * 58-pulsar-check-lifecycle (issue #97).
 *
 * The real Titan↔Pulsar seam needs two node-side surfaces, both of which this
 * fixture provides from ONE plain Node http server bound on the host:
 *
 *   1. git dumb-HTTP hosting of `<node>/<repo>.git` — PulsarEventSource
 *      (inside the titan-server container) shells out to
 *      `git fetch <node>/<repo>.git refs/pulsar/changes/<id>` to materialize
 *      a change revision before enqueueing a build. A bare mirror with
 *      `git update-server-info` run is fully servable as static files (git
 *      falls back to the dumb protocol when info/refs is not a smart
 *      advertisement) — verified against git 2.x before this shipped.
 *
 *   2. the check ledger — PulsarCheckReporter posts every check event to
 *      `POST /_pulsar/ledger/<repo>/changes/<changeId>/events` with body
 *      `{"kind":"ci","check":"build","conclusion":..(,"phase":..)}`
 *      (PulsarClient.postCheck). The fixture records every received event
 *      verbatim and answers 201 — that recording IS the spec's check-lifecycle
 *      oracle.
 *
 * The titan-server container reaches this host-bound server through
 * `host.docker.internal` (rig/local/docker-compose.yml maps it via
 * `extra_hosts: host-gateway` and points PULSAR_NODE_BASE_URL at port 18098),
 * which is why `listen` binds 0.0.0.0 and the port must match the rig's
 * PULSAR_NODE_BASE_URL.
 *
 * Ownership + litter: everything here is per-spec state — the git fixture
 * lives in an os.tmpdir() temp dir the caller removes, and the http server is
 * closed in the spec's finally block. Nothing touches the rig's shared DB.
 */
import { spawnSync } from 'node:child_process'
import * as fs from 'node:fs'
import * as http from 'node:http'
import * as os from 'node:os'
import * as path from 'node:path'

/** One check event the reporter posted to the fake ledger. */
export interface RecordedCheckEvent {
  /** URL-decoded repo segment from the ledger path. */
  repo: string
  /** URL-decoded change id segment from the ledger path. */
  changeId: string
  /** The JSON body as posted by PulsarClient.postCheck. */
  body: { kind?: string; check?: string; conclusion?: string; phase?: string }
  receivedAt: number
}

export interface FakePulsarNode {
  port: number
  /** Every ledger event received, in arrival order (all repos/changes). */
  events: RecordedCheckEvent[]
  /** Events for one (repo, changeId), in arrival order. */
  eventsFor(repo: string, changeId: string): RecordedCheckEvent[]
  close(): Promise<void>
}

export interface PulsarChangeFixture {
  /** Temp root to `fs.rm(root, {recursive:true})` in the spec's finally. */
  root: string
  /** The bare mirror served as `<node>/<repo>.git`. */
  bareDir: string
  /** The commit oid published under refs/pulsar/changes/<changeId>. */
  revision: string
}

/** Run one git command; throw with full output on any failure. */
function git(cwd: string, ...args: string[]): string {
  const res = spawnSync('git', args, { cwd, encoding: 'utf8' })
  if (res.error) throw new Error(`git ${args[0]} could not start: ${res.error.message}`)
  if (res.status !== 0) {
    throw new Error(
      `git ${args.join(' ')} exit=${res.status}: ${res.stdout ?? ''}${res.stderr ?? ''}`,
    )
  }
  return res.stdout ?? ''
}

/**
 * Build the on-disk change fixture: a work repo whose single commit carries
 * `.titan/pipelines/ci.yml` (PulsarEventSource discovery requires the file to
 * EXIST in the change tree — a change without it is an honest no-op), publish
 * that commit under `refs/pulsar/changes/<changeId>`, mirror it bare, and run
 * `git update-server-info` so the mirror is dumb-HTTP servable.
 */
export function buildChangeFixture(
  repo: string,
  changeId: string,
  pipelineYaml: string,
): PulsarChangeFixture {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'titan-e2e-pulsar-'))
  const work = path.join(root, 'work')
  fs.mkdirSync(path.join(work, '.titan', 'pipelines'), { recursive: true })
  fs.writeFileSync(path.join(work, '.titan', 'pipelines', 'ci.yml'), pipelineYaml)

  git(work, 'init', '-q', '-b', 'main', '.')
  git(work, 'add', '-A')
  git(
    work,
    '-c',
    'user.email=e2e@titan',
    '-c',
    'user.name=titan-e2e',
    'commit',
    '-q',
    '-m',
    'pulsar change fixture',
  )
  const revision = git(work, 'rev-parse', 'HEAD').trim()
  git(work, 'update-ref', `refs/pulsar/changes/${changeId}`, revision)

  const bareDir = path.join(root, `${repo}.git`)
  git(root, 'clone', '-q', '--mirror', work, bareDir)
  git(bareDir, 'update-server-info')
  return { root, bareDir, revision }
}

const LEDGER_RE = /^\/_pulsar\/ledger\/([^/]+)\/changes\/([^/]+)\/events$/
const GIT_RE = /^\/([^/]+\.git)\/(.+)$/

/**
 * Start the fake node on `port` (0.0.0.0 — the docker rig dials in through
 * host-gateway). `repos` maps `<repo>` (WITHOUT the .git suffix) to the bare
 * mirror directory to serve for it.
 */
export function startFakePulsarNode(
  port: number,
  repos: Map<string, string>,
): Promise<FakePulsarNode> {
  const events: RecordedCheckEvent[] = []

  const server = http.createServer((req, res) => {
    const url = new URL(req.url ?? '/', 'http://localhost')

    // ── ledger: record the reporter's check events ─────────────────────────
    if (req.method === 'POST') {
      const m = LEDGER_RE.exec(url.pathname)
      if (!m) {
        res.writeHead(404).end()
        return
      }
      const chunks: Buffer[] = []
      req.on('data', (c: Buffer) => chunks.push(c))
      req.on('end', () => {
        let body: RecordedCheckEvent['body'] = {}
        try {
          body = JSON.parse(Buffer.concat(chunks).toString('utf8')) as RecordedCheckEvent['body']
        } catch {
          // keep {} — the spec's shape assertions will surface it
        }
        events.push({
          repo: decodeURIComponent(m[1]!),
          changeId: decodeURIComponent(m[2]!),
          body,
          receivedAt: Date.now(),
        })
        res.writeHead(201, { 'content-type': 'application/json' }).end('{}')
      })
      return
    }

    // ── git dumb-HTTP: static files out of the bare mirror ─────────────────
    if (req.method === 'GET') {
      const m = GIT_RE.exec(url.pathname)
      if (m) {
        const repoName = decodeURIComponent(m[1]!).replace(/\.git$/, '')
        const bareDir = repos.get(repoName)
        if (bareDir) {
          const file = path.resolve(bareDir, m[2]!)
          // Traversal guard: never serve outside the bare mirror.
          if (file.startsWith(path.resolve(bareDir) + path.sep) && fs.existsSync(file)) {
            const stat = fs.statSync(file)
            if (stat.isFile()) {
              res.writeHead(200, {
                'content-type': 'application/octet-stream',
                'content-length': stat.size,
              })
              fs.createReadStream(file).pipe(res)
              return
            }
          }
        }
      }
    }
    res.writeHead(404).end()
  })

  return new Promise((resolve, reject) => {
    server.once('error', (err: NodeJS.ErrnoException) => {
      reject(
        new Error(
          err.code === 'EADDRINUSE'
            ? `fake pulsar node port ${port} is already bound — is another spec run live, ` +
              `or does something else own :${port}? The rig's PULSAR_NODE_BASE_URL pins this port.`
            : `fake pulsar node failed to start: ${err.message}`,
        ),
      )
    })
    server.listen(port, '0.0.0.0', () => {
      resolve({
        port,
        events,
        eventsFor: (repo, changeId) =>
          events.filter((e) => e.repo === repo && e.changeId === changeId),
        close: () =>
          new Promise<void>((res2) => {
            server.close(() => res2())
            // Do not wait on keep-alive sockets — the JDK client may hold one.
            server.closeAllConnections?.()
          }),
      })
    })
  })
}
