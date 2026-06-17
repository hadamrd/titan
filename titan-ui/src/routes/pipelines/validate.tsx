/**
 * /pipelines/validate — preview / validate a pipeline YAML without persisting
 * anything (closes #745).
 *
 * Closes the author-feedback loop: paste YAML, hit Validate, see the parser's
 * verdict inline — line/column-located errors when the YAML library supplied
 * them, otherwise the parser's structural message. On success, a bounded
 * summary (stage names + step counts + trigger types) renders so the author
 * can sanity-check "yes, the engine sees what I wrote".
 *
 * Persistence: the textarea contents are mirrored to localStorage on every
 * keystroke and re-hydrated on mount, so reload preserves the YAML (the brief
 * forbids URL params — sensitive YAML must not land in browser history).
 *
 * Visual: highlight.js for the editor isn't practical (textareas don't host
 * spans), so we keep a monospace textarea + render the verdict pane below
 * with the same .hljs-keyed styling the build-detail YAML tab uses.
 */
import { createFileRoute } from '@tanstack/react-router'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button } from '@/components/ui/Button'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { ApiError, type ProblemJson } from '@/api/types'
import { getAccessToken } from '@/auth/tokenStore'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

// ── DTOs (mirrors titan-server/.../dto/PipelineValidation*.java) ─────────────

export type PipelineValidationError = {
  line?: number
  column?: number
  message: string
}

export type PipelineStageSummary = {
  name: string
  stepCount: number
}

export type PipelineTriggerSummary = {
  type: string
  expression?: string
}

export type PipelineModelSummary = {
  stages: PipelineStageSummary[]
  triggers: PipelineTriggerSummary[]
}

export type PipelineValidationResult = {
  valid: boolean
  errors: PipelineValidationError[]
  summary?: PipelineModelSummary | null
}

// ── API call ─────────────────────────────────────────────────────────────────

// Same-origin: nginx proxies /api → titan-server. Closes #898.
const BASE_URL = ''

export async function validatePipelineYaml(yaml: string): Promise<PipelineValidationResult> {
  const token = getAccessToken()
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const res = await fetch(`${BASE_URL}/api/v1/pipeline/validate`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ yaml }),
  })
  if (!res.ok) {
    let problem: ProblemJson
    try {
      problem = (await res.json()) as ProblemJson
    } catch {
      problem = {
        type: 'about:blank',
        title: res.statusText || 'Unknown error',
        status: res.status,
        detail: null,
        instance: null,
      }
    }
    throw new ApiError(res.status, problem)
  }
  return (await res.json()) as PipelineValidationResult
}

// ── Route ────────────────────────────────────────────────────────────────────

const LOCAL_STORAGE_KEY = 'titan.pipelines.validate.yaml'

export const Route = createFileRoute('/pipelines/validate')({
  component: PipelineValidatePage,
})

export function PipelineValidatePage() {
  useDocumentTitle('Validate pipeline · Titan')

  const [yaml, setYaml] = useState<string>('')
  const [result, setResult] = useState<PipelineValidationResult | null>(null)
  const [networkError, setNetworkError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  // Re-hydrate from localStorage on mount.
  useEffect(() => {
    try {
      const saved = localStorage.getItem(LOCAL_STORAGE_KEY)
      if (saved) setYaml(saved)
    } catch {
      // localStorage unavailable (private mode, quota) — silent fallback to empty.
    }
  }, [])

  // Persist on every change — debouncing is overkill at typical YAML sizes.
  useEffect(() => {
    try {
      localStorage.setItem(LOCAL_STORAGE_KEY, yaml)
    } catch {
      // ignore
    }
  }, [yaml])

  const canSubmit = useMemo(() => yaml.trim().length > 0 && !submitting, [yaml, submitting])

  const onValidate = useCallback(async () => {
    setSubmitting(true)
    setNetworkError(null)
    try {
      const r = await validatePipelineYaml(yaml)
      setResult(r)
    } catch (e) {
      if (e instanceof ApiError) {
        setNetworkError(`${e.status}: ${e.problem.detail ?? e.problem.title}`)
      } else {
        setNetworkError(e instanceof Error ? e.message : 'request failed')
      }
      setResult(null)
    } finally {
      setSubmitting(false)
    }
  }, [yaml])

  return (
    <PageContainer width="default" data-testid="pipeline-validate-page">
      <PageHeader
        title="Validate pipeline"
        description={
          <>
            Paste a Titan pipeline YAML and click <em>Validate</em>. The server runs the same
            parser the bake step uses — nothing is saved.
          </>
        }
      />

      <label htmlFor="pipeline-yaml" className="text-xs font-medium uppercase text-muted-foreground">
        Pipeline YAML
      </label>
      <textarea
        id="pipeline-yaml"
        data-testid="pipeline-yaml-input"
        className="font-mono text-sm w-full h-80 mt-1 p-3 border rounded bg-card"
        spellCheck={false}
        value={yaml}
        onChange={(e) => setYaml(e.target.value)}
        placeholder={'stages:\n  - stage: Build\n    steps:\n      - sh: echo hi\n'}
      />

      <div className="mt-3 flex items-center gap-2">
        <Button
          data-testid="pipeline-validate-submit"
          onClick={onValidate}
          disabled={!canSubmit}
        >
          {submitting ? 'Validating…' : 'Validate'}
        </Button>
        {networkError && (
          <span className="text-sm text-destructive" data-testid="pipeline-validate-network-error">
            {networkError}
          </span>
        )}
      </div>

      {result && (
        <div className="mt-6" data-testid="pipeline-validate-result">
          {result.valid ? (
            <ValidVerdict summary={result.summary ?? { stages: [], triggers: [] }} />
          ) : (
            <InvalidVerdict errors={result.errors} />
          )}
        </div>
      )}
    </PageContainer>
  )
}

function ValidVerdict({ summary }: { summary: PipelineModelSummary }) {
  return (
    <section data-testid="pipeline-validate-valid">
      <div className="text-sm font-semibold text-green-600 mb-3">Valid pipeline.</div>
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        <div>
          <h2 className="text-xs uppercase font-medium text-muted-foreground mb-2">
            Stages ({summary.stages.length})
          </h2>
          {summary.stages.length === 0 ? (
            <div className="text-sm text-muted-foreground">No stages declared.</div>
          ) : (
            <ul className="text-sm space-y-1" data-testid="pipeline-validate-stages">
              {summary.stages.map((s) => (
                <li key={s.name} className="flex justify-between border-b py-1">
                  <span>{s.name}</span>
                  <span className="text-muted-foreground">{s.stepCount} step(s)</span>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div>
          <h2 className="text-xs uppercase font-medium text-muted-foreground mb-2">
            Triggers ({summary.triggers.length})
          </h2>
          {summary.triggers.length === 0 ? (
            <div className="text-sm text-muted-foreground">No triggers declared.</div>
          ) : (
            <ul className="text-sm space-y-1" data-testid="pipeline-validate-triggers">
              {summary.triggers.map((t, i) => (
                <li key={`${t.type}-${i}`} className="flex justify-between border-b py-1">
                  <span>{t.type}</span>
                  <span className="text-muted-foreground font-mono">{t.expression ?? ''}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  )
}

function InvalidVerdict({ errors }: { errors: PipelineValidationError[] }) {
  return (
    <section data-testid="pipeline-validate-invalid">
      <div className="text-sm font-semibold text-destructive mb-3">
        Invalid pipeline ({errors.length} error{errors.length === 1 ? '' : 's'})
      </div>
      <ul className="text-sm space-y-2" data-testid="pipeline-validate-errors">
        {errors.map((err, i) => (
          <li
            key={i}
            className="border-l-2 border-destructive pl-3 py-1"
            data-testid="pipeline-validate-error"
          >
            {(err.line != null || err.column != null) && (
              <span className="font-mono text-xs text-muted-foreground mr-2">
                line {err.line ?? '?'}
                {err.column != null ? `:${err.column}` : ''}
              </span>
            )}
            <span>{err.message}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
