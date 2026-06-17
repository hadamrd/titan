import { Plus } from 'lucide-react'
import { PAT_SCOPES, type PatScope } from '@/api/types'
import { Button } from '@/components/ui/Button'
import { Input } from '@/components/ui/Input'
import { cn } from '@/lib/utils'

interface CreateTokenFormProps {
  name: string
  onNameChange: (v: string) => void
  selectedScopes: PatScope[]
  onToggleScope: (s: PatScope) => void
  /** #1082 — optional job-pattern glob (e.g. "acme/web-*"). Empty = no restriction. */
  jobPattern: string
  onJobPatternChange: (v: string) => void
  onSubmit: () => void
  pending: boolean
  errorMessage: string | null
}

/**
 * Create-token form. Rewritten to the UX chart (H3/H5/H6): a single
 * constrained-width column of stacked field-groups — label, control, helper
 * text *beneath* the field — instead of full-width flex rows that sprawled the
 * controls across the card with hints stranded far-right. Labels use
 * `text-foreground` (not the old muted `--fg-dim`, which failed contrast in
 * light mode); one dominant primary action at the foot.
 */
export function CreateTokenForm({
  name,
  onNameChange,
  selectedScopes,
  onToggleScope,
  jobPattern,
  onJobPatternChange,
  onSubmit,
  pending,
  errorMessage,
}: CreateTokenFormProps) {
  const submitDisabled = name.trim().length === 0 || pending

  function handleSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (submitDisabled) return
    onSubmit()
  }

  return (
    <form onSubmit={handleSubmit} className="max-w-xl space-y-5">
      {/* Token name */}
      <div className="space-y-1.5">
        <label htmlFor="pat-name" className="block text-sm font-medium text-foreground">
          Token name
        </label>
        <Input
          id="pat-name"
          name="pat-name"
          placeholder="e.g. ci-bot"
          value={name}
          onChange={(e) => onNameChange(e.target.value)}
          maxLength={100}
          data-testid="pat-name-input"
          aria-label="Token name"
        />
      </div>

      {/* Scopes */}
      <div className="space-y-1.5">
        <span className="block text-sm font-medium text-foreground">Scopes</span>
        <div data-testid="pat-scopes" className="flex flex-wrap gap-2">
          {PAT_SCOPES.map((scope) => {
            const checked = selectedScopes.includes(scope)
            return (
              <label
                key={scope}
                className={cn(
                  'inline-flex cursor-pointer select-none items-center gap-1.5 rounded-md border px-2.5 py-1 font-mono text-xs transition-colors',
                  checked
                    ? 'border-primary/60 bg-primary/10 text-foreground'
                    : 'border-border text-muted-foreground hover:border-input hover:text-foreground',
                )}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => onToggleScope(scope)}
                  data-testid={`pat-scope-${scope}`}
                  className="h-3 w-3 accent-[hsl(var(--primary))]"
                />
                {scope}
              </label>
            )
          })}
        </div>
        <p className="text-xs text-muted-foreground">
          {selectedScopes.length === 0
            ? 'None selected — the token inherits all your roles.'
            : `${selectedScopes.length} scope${selectedScopes.length > 1 ? 's' : ''} selected.`}
        </p>
      </div>

      {/* Job pattern — #1082 optional glob; server is source of truth on validation */}
      <div className="space-y-1.5">
        <label
          htmlFor="pat-job-pattern-input"
          className="block text-sm font-medium text-foreground"
        >
          Job pattern <span className="font-normal text-muted-foreground">(optional)</span>
        </label>
        <Input
          id="pat-job-pattern-input"
          name="pat-job-pattern"
          placeholder="e.g. acme/web-* or org/my-app/**"
          value={jobPattern}
          onChange={(e) => onJobPatternChange(e.target.value)}
          maxLength={200}
          className="font-mono"
          data-testid="pat-job-pattern-input"
          aria-label="Job pattern"
          autoComplete="off"
          spellCheck={false}
        />
        <p className="text-xs text-muted-foreground">
          {jobPattern.trim().length === 0
            ? 'Restrict the token to jobs matching a glob. Empty = any job in your scopes.'
            : 'Glob matched against each job’s full name.'}
        </p>
      </div>

      {errorMessage !== null && (
        <div role="alert" className="text-sm text-destructive">
          {errorMessage}
        </div>
      )}

      <Button
        type="submit"
        variant="default"
        disabled={submitDisabled}
        data-testid="pat-generate-btn"
      >
        {pending ? (
          <>Generating…</>
        ) : (
          <>
            <Plus size={14} aria-hidden /> Generate token
          </>
        )}
      </Button>
    </form>
  )
}
