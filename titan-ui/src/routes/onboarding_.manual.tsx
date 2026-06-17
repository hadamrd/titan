/**
 * Onboarding (manual) — fallback wizard for self-hosted GitLab / Gitea /
 * Bitbucket Server / air-gapped enterprise (design/63 §"Migration path").
 *
 * The GitHub App golden path lives at /onboarding now (design/63 §1–4); this
 * route is the prior 4-step "paste a Git URL" flow, kept alive verbatim for
 * SCMs the App doesn't cover. Linked from the footer of /onboarding.
 *
 * Originally Titan v3 "first 90 seconds" moment (§5.6 of titan-design-brief).
 * A 4-step wizard a new SRE walks the moment a Titan rig comes up:
 *   1. Connect a repo            — git URL or pick from a curated sample
 *   2. Set the trigger           — Run on demand · cron · github (default on-demand)
 *   3. Provide the pipeline YAML — default lint/build/test · paste · "I'll add it"
 *   4. Run                       — kicks the first build (or shows a fallback snippet)
 *
 * Backend reality: a "create job from URL + YAML" endpoint does NOT exist in
 * 0.1.0. Step 4 therefore degrades to one of two paths:
 *   - If at least one job already exists on this Titan instance, we trigger a
 *     build on that job (proves the rig is alive end-to-end).
 *   - Otherwise we show a copy-able `task dogfood:run` / `.titan/pipeline.yml`
 *     snippet — no fake success state.
 *
 * Dismissible: localStorage `titan.onboarding.dismissed = "1"` is set both on
 * the explicit "Skip" button and on first-build success. The Welcome card on
 * `/` reads the same key.
 */
import { useState } from 'react'
import { createFileRoute, Link, useNavigate } from '@tanstack/react-router'
import { ArrowLeft, ArrowRight, Check, GitBranch, Play, X } from 'lucide-react'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { PageContainer, PageHeader } from '@/components/ui/Page'
import { useJobs, useTriggerBuild } from '@/api/hooks'
import { ApiError } from '@/api/types'
import { useDocumentTitle } from '@/lib/useDocumentTitle'

// File is `onboarding_.manual.tsx` — TanStack file-based router treats the
// trailing-underscore segment as a NON-NESTED sibling of /onboarding (so the
// parent does not need to render <Outlet />). URL stays `/onboarding/manual`.
export const Route = createFileRoute('/onboarding_/manual')({
  component: OnboardingManualPage,
})

export const ONBOARDING_DISMISSED_KEY = 'titan.onboarding.dismissed'

const SAMPLE_REPOS: ReadonlyArray<{ label: string; url: string }> = [
  { label: 'hadamrd/titan', url: 'https://github.com/hadamrd/titan' },
  {
    label: 'spring-projects/spring-petclinic',
    url: 'https://github.com/spring-projects/spring-petclinic',
  },
] as const

const DEFAULT_PIPELINE_YAML = `# .titan/pipeline.yml
pipeline:
  - name: lint
    sh: echo "lint OK"
  - name: build
    sh: echo "build OK"
  - name: test
    sh: echo "tests OK"
`

type TriggerKind = 'demand' | 'cron' | 'github'
type YamlMode = 'default' | 'paste' | 'inrepo'

interface WizardState {
  step: 1 | 2 | 3 | 4
  repoUrl: string
  triggerKind: TriggerKind
  yamlMode: YamlMode
  pastedYaml: string
}

function OnboardingManualPage() {
  useDocumentTitle('Get started')
  const navigate = useNavigate()
  const jobs = useJobs(0, 1)
  const trigger = useTriggerBuild()
  const [state, setState] = useState<WizardState>({
    step: 1,
    repoUrl: '',
    triggerKind: 'demand',
    yamlMode: 'default',
    pastedYaml: '',
  })

  const firstJob = jobs.data?.items[0]
  const canTriggerBackend = firstJob !== undefined && firstJob.enabled

  function dismiss(): void {
    try {
      window.localStorage.setItem(ONBOARDING_DISMISSED_KEY, '1')
    } catch {
      /* localStorage may be unavailable in private mode — skip silently */
    }
    void navigate({ to: '/' })
  }

  function go(step: WizardState['step']): void {
    setState((s) => ({ ...s, step }))
  }

  function runFirstBuild(): void {
    if (!canTriggerBackend || !firstJob) return
    trigger.mutate(
      { jobId: firstJob.id },
      {
        onSuccess: (resp) => {
          try {
            window.localStorage.setItem(ONBOARDING_DISMISSED_KEY, '1')
          } catch {
            /* ignore */
          }
          void navigate({
            to: '/builds/$buildId',
            params: { buildId: String(resp.buildId) },
          })
        },
      },
    )
  }

  return (
    <PageContainer width="narrow">
      <PageHeader
        title="Let's run your first build"
        description="Four short steps. You'll be watching logs stream in inside a minute."
        actions={
          <Button variant="ghost" size="sm" onClick={dismiss}>
            <X size={12} aria-hidden /> Skip
          </Button>
        }
      />

      <Stepper step={state.step} />

      <div className="card" style={{ marginTop: 14 }}>
        {state.step === 1 && (
          <StepRepo
            value={state.repoUrl}
            onChange={(v) => setState((s) => ({ ...s, repoUrl: v }))}
            onNext={() => go(2)}
          />
        )}
        {state.step === 2 && (
          <StepTrigger
            value={state.triggerKind}
            onChange={(v) => setState((s) => ({ ...s, triggerKind: v }))}
            onBack={() => go(1)}
            onNext={() => go(3)}
          />
        )}
        {state.step === 3 && (
          <StepYaml
            mode={state.yamlMode}
            yaml={state.pastedYaml}
            onModeChange={(m) => setState((s) => ({ ...s, yamlMode: m }))}
            onYamlChange={(v) => setState((s) => ({ ...s, pastedYaml: v }))}
            onBack={() => go(2)}
            onNext={() => go(4)}
          />
        )}
        {state.step === 4 && (
          <StepRun
            repoUrl={state.repoUrl}
            canTrigger={canTriggerBackend}
            triggering={trigger.isPending}
            error={
              trigger.isError
                ? trigger.error instanceof ApiError
                  ? (trigger.error.problem.detail ?? 'Trigger failed.')
                  : 'Trigger failed.'
                : null
            }
            onBack={() => go(3)}
            onRun={runFirstBuild}
          />
        )}
      </div>
    </PageContainer>
  )
}

function Stepper({ step }: { step: 1 | 2 | 3 | 4 }) {
  const steps = ['Repo', 'Trigger', 'Pipeline', 'Run'] as const
  return (
    <div
      role="list"
      aria-label="Onboarding steps"
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(4, 1fr)',
        gap: 8,
        marginTop: 6,
      }}
    >
      {steps.map((label, i) => {
        const idx = (i + 1) as 1 | 2 | 3 | 4
        const done = step > idx
        const current = step === idx
        return (
          <div
            key={label}
            role="listitem"
            aria-current={current ? 'step' : undefined}
            style={{
              padding: '10px 12px',
              borderRadius: 6,
              background: 'var(--bg-2)',
              border: `1px solid ${current ? 'var(--accent)' : 'var(--line)'}`,
              fontSize: 12,
              fontFamily: 'var(--font-mono)',
              color: current ? 'var(--fg)' : done ? 'var(--fg-muted)' : 'var(--fg-dim)',
              display: 'flex',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <span
              style={{
                width: 18,
                height: 18,
                borderRadius: 999,
                background: done
                  ? 'var(--ok)'
                  : current
                    ? 'var(--accent)'
                    : 'transparent',
                border: done || current ? 'none' : '1px solid var(--line)',
                color: done || current ? '#000' : 'var(--fg-faint)',
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontSize: 10,
              }}
              aria-hidden
            >
              {done ? <Check size={10} /> : idx}
            </span>
            {label}
          </div>
        )
      })}
    </div>
  )
}

function StepRepo({
  value,
  onChange,
  onNext,
}: {
  value: string
  onChange: (v: string) => void
  onNext: () => void
}) {
  return (
    <>
      <div className="card-header">
        <h3 className="card-title">Step 1 — Connect a repository</h3>
        <span className="card-sub" style={{ marginLeft: 'auto' }}>
          public Git URL · no clone required yet
        </span>
      </div>
      <div className="card-body" style={{ display: 'grid', gap: 14 }}>
        <label style={{ display: 'grid', gap: 6 }}>
          <span style={{ fontSize: 12, color: 'var(--fg-muted)' }}>Git URL</span>
          <Input
            placeholder="https://github.com/your-org/your-repo"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            aria-label="Git URL"
          />
        </label>
        <div>
          <div style={{ fontSize: 11, color: 'var(--fg-dim)', marginBottom: 6 }}>
            …or pick a sample:
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {SAMPLE_REPOS.map((r) => (
              <button
                key={r.url}
                type="button"
                className="btn btn-sm btn-ghost"
                onClick={() => onChange(r.url)}
              >
                <GitBranch size={11} aria-hidden /> {r.label}
              </button>
            ))}
          </div>
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
          <Button size="sm" onClick={onNext} disabled={value.trim().length === 0}>
            Continue <ArrowRight size={12} aria-hidden />
          </Button>
        </div>
      </div>
    </>
  )
}

function StepTrigger({
  value,
  onChange,
  onBack,
  onNext,
}: {
  value: TriggerKind
  onChange: (v: TriggerKind) => void
  onBack: () => void
  onNext: () => void
}) {
  const options: Array<{ value: TriggerKind; title: string; sub: string }> = [
    {
      value: 'demand',
      title: 'Run on demand',
      sub: 'Fastest path to green — trigger manually from the UI.',
    },
    {
      value: 'cron',
      title: 'On a schedule',
      sub: 'e.g. every 15 minutes. Configurable later.',
    },
    {
      value: 'github',
      title: 'On GitHub push',
      sub: 'Webhook URL + secret will be generated.',
    },
  ]
  return (
    <>
      <div className="card-header">
        <h3 className="card-title">Step 2 — When should it run?</h3>
        <span className="card-sub" style={{ marginLeft: 'auto' }}>
          you can change this anytime
        </span>
      </div>
      <div className="card-body">
        <RadioList name="trigger-kind" value={value} onChange={onChange} options={options} />
        <div
          style={{
            marginTop: 14,
            display: 'flex',
            justifyContent: 'space-between',
          }}
        >
          <Button variant="ghost" size="sm" onClick={onBack}>
            <ArrowLeft size={12} aria-hidden /> Back
          </Button>
          <Button size="sm" onClick={onNext}>
            Continue <ArrowRight size={12} aria-hidden />
          </Button>
        </div>
      </div>
    </>
  )
}

function StepYaml({
  mode,
  yaml,
  onModeChange,
  onYamlChange,
  onBack,
  onNext,
}: {
  mode: YamlMode
  yaml: string
  onModeChange: (m: YamlMode) => void
  onYamlChange: (v: string) => void
  onBack: () => void
  onNext: () => void
}) {
  const options: Array<{ value: YamlMode; title: string; sub: string }> = [
    {
      value: 'default',
      title: 'Use a default lint → build → test pipeline',
      sub: 'A minimal three-step pipeline we generate for you.',
    },
    {
      value: 'paste',
      title: 'Paste my pipeline YAML',
      sub: 'For when you already have one in mind.',
    },
    {
      value: 'inrepo',
      title: "I'll commit .titan/pipeline.yml to the repo",
      sub: 'Best for production. See the PDL docs.',
    },
  ]
  return (
    <>
      <div className="card-header">
        <h3 className="card-title">Step 3 — Pipeline</h3>
        <span className="card-sub" style={{ marginLeft: 'auto' }}>
          PDL · declarative
        </span>
      </div>
      <div className="card-body">
        <RadioList name="yaml-mode" value={mode} onChange={onModeChange} options={options} />
        {mode === 'default' && (
          <pre
            style={{
              marginTop: 12,
              background: 'var(--bg-2)',
              border: '1px solid var(--line)',
              borderRadius: 6,
              padding: 12,
              fontSize: 12,
              fontFamily: 'var(--font-mono)',
              color: 'var(--fg-muted)',
              overflowX: 'auto',
            }}
          >
            {DEFAULT_PIPELINE_YAML}
          </pre>
        )}
        {mode === 'paste' && (
          <textarea
            aria-label="Pipeline YAML"
            spellCheck={false}
            value={yaml}
            onChange={(e) => onYamlChange(e.target.value)}
            placeholder={DEFAULT_PIPELINE_YAML}
            style={{
              marginTop: 12,
              width: '100%',
              minHeight: 180,
              background: 'var(--bg-2)',
              border: '1px solid var(--line)',
              borderRadius: 6,
              padding: 12,
              fontSize: 12,
              fontFamily: 'var(--font-mono)',
              color: 'var(--fg)',
              resize: 'vertical',
            }}
          />
        )}
        {mode === 'inrepo' && (
          <div
            style={{
              marginTop: 12,
              padding: 12,
              background: 'var(--bg-2)',
              border: '1px solid var(--line)',
              borderRadius: 6,
              fontSize: 12,
              color: 'var(--fg-muted)',
            }}
          >
            Commit a <code>.titan/pipeline.yml</code> to your repo&apos;s default branch.
            The PDL reference lives in <code>docs/pdl/</code>.
          </div>
        )}
        <div
          style={{
            marginTop: 14,
            display: 'flex',
            justifyContent: 'space-between',
          }}
        >
          <Button variant="ghost" size="sm" onClick={onBack}>
            <ArrowLeft size={12} aria-hidden /> Back
          </Button>
          <Button
            size="sm"
            onClick={onNext}
            disabled={mode === 'paste' && yaml.trim().length === 0}
          >
            Continue <ArrowRight size={12} aria-hidden />
          </Button>
        </div>
      </div>
    </>
  )
}

function StepRun({
  repoUrl,
  canTrigger,
  triggering,
  error,
  onBack,
  onRun,
}: {
  repoUrl: string
  canTrigger: boolean
  triggering: boolean
  error: string | null
  onBack: () => void
  onRun: () => void
}) {
  return (
    <>
      <div className="card-header">
        <h3 className="card-title">Step 4 — Run it</h3>
        <span className="card-sub" style={{ marginLeft: 'auto' }}>
          we&apos;ll navigate you to the live log
        </span>
      </div>
      <div className="card-body" style={{ display: 'grid', gap: 14 }}>
        <div
          style={{
            padding: 12,
            background: 'var(--bg-2)',
            border: '1px solid var(--line)',
            borderRadius: 6,
            fontFamily: 'var(--font-mono)',
            fontSize: 12,
            color: 'var(--fg-muted)',
            wordBreak: 'break-all',
          }}
        >
          {repoUrl || '(no repo set — go back to step 1)'}
        </div>
        {canTrigger ? (
          <div style={{ fontSize: 12, color: 'var(--fg-dim)' }}>
            We&apos;ll trigger the first build on your existing job to prove the rig
            is alive end-to-end. Job creation from a URL is on the 0.1.0 roadmap.
          </div>
        ) : (
          <div
            style={{
              padding: 12,
              background: 'var(--bg-2)',
              border: '1px solid var(--warn)',
              borderRadius: 6,
              fontSize: 12,
              color: 'var(--fg-muted)',
            }}
          >
            <div style={{ marginBottom: 6 }}>
              No job exists yet on this Titan rig. For 0.1.0, jobs are created
              out-of-band. Drop a <code>.titan/pipeline.yml</code> in your repo and run:
            </div>
            <pre
              style={{
                margin: 0,
                fontFamily: 'var(--font-mono)',
                fontSize: 12,
                color: 'var(--fg)',
              }}
            >{`task dogfood:run REPO=${repoUrl || '<git-url>'}`}</pre>
          </div>
        )}
        {error && (
          <div style={{ fontSize: 12, color: 'var(--fail)' }} role="alert">
            {error}
          </div>
        )}
        <div style={{ display: 'flex', justifyContent: 'space-between' }}>
          <Button variant="ghost" size="sm" onClick={onBack}>
            <ArrowLeft size={12} aria-hidden /> Back
          </Button>
          <div style={{ display: 'flex', gap: 8 }}>
            <Link to="/pipelines" className="btn btn-sm btn-ghost">
              View pipelines
            </Link>
            <Button
              size="sm"
              onClick={onRun}
              disabled={!canTrigger || triggering}
              aria-label="Run first build"
            >
              <Play size={12} aria-hidden />
              {triggering ? 'Starting…' : 'Run first build'}
            </Button>
          </div>
        </div>
      </div>
    </>
  )
}

function RadioList<T extends string>({
  name,
  value,
  onChange,
  options,
}: {
  name: string
  value: T
  onChange: (v: T) => void
  options: ReadonlyArray<{ value: T; title: string; sub: string }>
}) {
  return (
    <div role="radiogroup" aria-label={name} style={{ display: 'grid', gap: 8 }}>
      {options.map((opt) => {
        const selected = opt.value === value
        return (
          <label
            key={opt.value}
            style={{
              display: 'flex',
              alignItems: 'flex-start',
              gap: 10,
              padding: '10px 12px',
              borderRadius: 6,
              background: 'var(--bg-2)',
              border: `1px solid ${selected ? 'var(--accent)' : 'var(--line)'}`,
              cursor: 'pointer',
            }}
          >
            <input
              type="radio"
              name={name}
              value={opt.value}
              checked={selected}
              onChange={() => onChange(opt.value)}
              style={{ marginTop: 3 }}
            />
            <div style={{ display: 'grid', gap: 2 }}>
              <span style={{ fontSize: 13, fontWeight: 500 }}>{opt.title}</span>
              <span style={{ fontSize: 11, color: 'var(--fg-dim)' }}>{opt.sub}</span>
            </div>
          </label>
        )
      })}
    </div>
  )
}
