import { useState } from 'react'
import { useCreateToken, useMyTokens, useRevokeToken } from '@/api/hooks'
import type {
  PatScope,
  PersonalAccessTokenCreatedDto,
  PersonalAccessTokenDto,
} from '@/api/types'
import { ConfirmDialog } from '@/components/ui/ConfirmDialog'
import { CreateTokenForm } from './CreateTokenForm'
import { PatTable } from './PatTable'
import { TokenRevealCard } from './TokenRevealCard'

export function PersonalAccessTokensCard() {
  const { data: tokens, isLoading, error, refetch } = useMyTokens()
  const create = useCreateToken()
  const revoke = useRevokeToken()
  const [name, setName] = useState('')
  const [selectedScopes, setSelectedScopes] = useState<PatScope[]>([])
  // #1082: optional job-pattern glob (e.g. "acme/web-*"). Empty string = no
  // path restriction. The server normalises blank → null on insert.
  const [jobPattern, setJobPattern] = useState('')
  const [revokeTarget, setRevokeTarget] = useState<PersonalAccessTokenDto | null>(null)
  const [justCreated, setJustCreated] = useState<PersonalAccessTokenCreatedDto | null>(
    null,
  )

  function toggleScope(scope: PatScope) {
    setSelectedScopes((prev) =>
      prev.includes(scope) ? prev.filter((s) => s !== scope) : [...prev, scope],
    )
  }

  function submitCreate() {
    const trimmedPattern = jobPattern.trim()
    create.mutate(
      {
        name: name.trim(),
        scopes: selectedScopes.length > 0 ? selectedScopes : undefined,
        // Send undefined (rather than empty string) so the server's null-means-
        // no-restriction branch lights up.
        jobPattern: trimmedPattern.length > 0 ? trimmedPattern : undefined,
      },
      {
        onSuccess: (created) => {
          setJustCreated(created)
          setName('')
          setSelectedScopes([])
          setJobPattern('')
        },
      },
    )
  }

  function onRevoke(t: PersonalAccessTokenDto) {
    if (t.revokedAt !== null && t.revokedAt !== undefined) return
    setRevokeTarget(t)
  }

  function confirmRevoke() {
    if (!revokeTarget) return
    revoke.mutate(
      { id: revokeTarget.id },
      {
        onSettled: () => setRevokeTarget(null),
      },
    )
  }

  const createErrorMessage =
    create.error !== null && create.error instanceof Error ? create.error.message : null

  return (
    // The section title/description is owned by the shared SectionCard shell in
    // profile.tsx (H8) — this component renders only the body: the reveal-once
    // card, the create form (the one primary action), then the tokens table.
    // (This supersedes #1201's local Card-with-header frame: that header now
    // lives in the shared SectionCard shell, so duplicating it here would double
    // the title + card chrome.)
    <div data-testid="pat-card" style={{ display: 'grid', gap: 16 }}>
      {justCreated !== null && (
        <TokenRevealCard created={justCreated} onDismiss={() => setJustCreated(null)} />
      )}

      <CreateTokenForm
        name={name}
        onNameChange={setName}
        selectedScopes={selectedScopes}
        onToggleScope={toggleScope}
        jobPattern={jobPattern}
        onJobPatternChange={setJobPattern}
        onSubmit={submitCreate}
        pending={create.isPending}
        errorMessage={createErrorMessage}
      />

      <PatTable
        tokens={tokens}
        isLoading={isLoading}
        error={error}
        onRetry={() => {
          void refetch()
        }}
        onRevoke={onRevoke}
        revokePending={revoke.isPending}
      />
      <ConfirmDialog
        open={revokeTarget !== null}
        title="Revoke access token"
        message={
          revokeTarget
            ? `Revoke token "${revokeTarget.name}"? Any client using it will immediately lose access. This cannot be undone.`
            : ''
        }
        confirmLabel="Revoke token"
        destructive
        busy={revoke.isPending}
        onConfirm={confirmRevoke}
        onCancel={() => !revoke.isPending && setRevokeTarget(null)}
        testId="revoke-token-dialog"
      />
    </div>
  )
}
