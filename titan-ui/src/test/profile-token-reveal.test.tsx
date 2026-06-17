/**
 * Smoke tests for the extracted TokenRevealCard.
 *
 * Covers:
 *  - renders the plaintext token and the copy/dismiss controls
 *  - clicking "I've copied it" calls onDismiss
 *  - copy button uses navigator.clipboard.writeText and flips label to "Copied"
 *  - sad path: clipboard write rejection does not throw, label stays "Copy"
 */
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

import { TokenRevealCard } from '../components/profile/TokenRevealCard'
import type { PersonalAccessTokenCreatedDto } from '../api/types'

const baseCreated: PersonalAccessTokenCreatedDto = {
  id: 1,
  name: 'ci-bot',
  prefix: 'titan_abc',
  token: 'titan_abc_secret_plaintext_DO_NOT_LEAK',
  createdAt: '2026-01-01T00:00:00Z',
  scopes: null,
  jobPattern: null,
}

function withClipboard(impl: (text: string) => Promise<void>) {
  Object.defineProperty(globalThis.navigator, 'clipboard', {
    value: { writeText: impl },
    configurable: true,
  })
}

describe('TokenRevealCard', () => {
  it('renders the plaintext secret and labels', () => {
    render(<TokenRevealCard created={baseCreated} onDismiss={() => {}} />)
    expect(screen.getByTestId('token-secret').textContent).toContain(
      'titan_abc_secret_plaintext_DO_NOT_LEAK',
    )
    expect(screen.getByText('Token created — copy it now.')).toBeTruthy()
    expect(screen.getByRole('button', { name: /copy/i })).toBeTruthy()
  })

  it('fires onDismiss when "I\'ve copied it" is clicked', () => {
    const onDismiss = vi.fn()
    render(<TokenRevealCard created={baseCreated} onDismiss={onDismiss} />)
    fireEvent.click(screen.getByRole('button', { name: /i've copied it/i }))
    expect(onDismiss).toHaveBeenCalledTimes(1)
  })

  it('copy button writes secret to clipboard and flips label to Copied', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    withClipboard(writeText)
    render(<TokenRevealCard created={baseCreated} onDismiss={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /^copy$/i }))
    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(baseCreated.token)
      expect(screen.getByRole('button', { name: /copied/i })).toBeTruthy()
    })
  })

  it('survives a clipboard rejection without throwing or flipping label', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('blocked'))
    withClipboard(writeText)
    render(<TokenRevealCard created={baseCreated} onDismiss={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: /^copy$/i }))
    await waitFor(() => {
      expect(writeText).toHaveBeenCalled()
    })
    // label stayed at "Copy" — sad path is swallowed (text is selectable)
    expect(screen.getByRole('button', { name: /^copy$/i })).toBeTruthy()
  })
})
