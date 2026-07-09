/**
 * ConfirmDialog — adversarial unit tests (closes #1039 unit slice).
 *
 * The dialog is the themed replacement for native window.confirm() used
 * across PAT revoke and (future) queue drain flows. Behaviours pinned here:
 *  - role="alertdialog" with aria-modal (a11y contract)
 *  - ESC key closes the dialog (calls onCancel) when not busy
 *  - ESC is a no-op while busy (mid-mutation; user must wait it out)
 *  - busy=true disables BOTH confirm and cancel buttons
 *  - destructive=true gives the confirm button the destructive variant
 *  - Backdrop click closes only when not busy
 */
import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ConfirmDialog } from '../components/ui/ConfirmDialog'

function defaults(overrides: Partial<React.ComponentProps<typeof ConfirmDialog>> = {}) {
  return {
    open: true,
    title: 'Delete thing',
    message: 'Are you sure you want to delete this thing?',
    onConfirm: vi.fn(),
    onCancel: vi.fn(),
    ...overrides,
  }
}

describe('ConfirmDialog', () => {
  it('does not render anything when open=false', () => {
    const { container } = render(<ConfirmDialog {...defaults({ open: false })} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders an alertdialog with the title + message when open=true', () => {
    render(<ConfirmDialog {...defaults()} />)
    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toBeInTheDocument()
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveTextContent('Delete thing')
    expect(dialog).toHaveTextContent('Are you sure')
  })

  it('ESC key closes the dialog (calls onCancel) when not busy', () => {
    const onCancel = vi.fn()
    render(<ConfirmDialog {...defaults({ onCancel })} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('ESC is suppressed while busy=true', () => {
    const onCancel = vi.fn()
    render(<ConfirmDialog {...defaults({ onCancel, busy: true })} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('busy=true disables both Cancel and Confirm buttons', () => {
    render(
      <ConfirmDialog
        {...defaults({
          busy: true,
          confirmLabel: 'Delete',
          cancelLabel: 'Keep',
        })}
      />,
    )
    // While busy, the confirm button label flips to "Working…"
    const cancel = screen.getByRole('button', { name: 'Keep' })
    const confirm = screen.getByRole('button', { name: /Working/ })
    expect(cancel).toBeDisabled()
    expect(confirm).toBeDisabled()
  })

  it('destructive=true gives the confirm button the destructive variant', () => {
    render(
      <ConfirmDialog
        {...defaults({ destructive: true, confirmLabel: 'Revoke', testId: 'pat' })}
      />,
    )
    const confirm = screen.getByTestId('pat-confirm')
    // Button component applies the destructive variant by class. We don't
    // pin the exact class name (could be Tailwind or CSS module); instead we
    // assert it carries a danger/destructive marker class.
    const cls = confirm.className
    expect(cls).toMatch(/destructive|danger/)
  })

  it('clicking the backdrop calls onCancel when not busy', () => {
    const onCancel = vi.fn()
    render(<ConfirmDialog {...defaults({ onCancel, testId: 'pat' })} />)
    fireEvent.click(screen.getByTestId('pat-backdrop'))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('clicking the backdrop is a no-op while busy=true', () => {
    const onCancel = vi.fn()
    render(<ConfirmDialog {...defaults({ onCancel, busy: true, testId: 'pat' })} />)
    fireEvent.click(screen.getByTestId('pat-backdrop'))
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('clicking Confirm fires onConfirm exactly once', () => {
    const onConfirm = vi.fn()
    render(
      <ConfirmDialog
        {...defaults({ onConfirm, confirmLabel: 'Yes do it', testId: 'pat' })}
      />,
    )
    fireEvent.click(screen.getByTestId('pat-confirm'))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  // ── #113: portal semantics ─────────────────────────────────────────────
  // Rendered inline, the fixed backdrop was captured by transformed/animated
  // ancestors (`.tab-pane` fade-in), offsetting it from the viewport and
  // letting the `.btn:active` micro-shift steal clicks to the backdrop.
  // jsdom can't reproduce the gesture physics (the e2e revoke spec is the
  // real proof); here we pin the structural fix: the dialog escapes the
  // React tree via a portal to <body>.

  it('portals the backdrop to document.body, NOT into the render container (#113)', () => {
    const { container } = render(<ConfirmDialog {...defaults({ testId: 'pat' })} />)
    const backdrop = screen.getByTestId('pat-backdrop')
    // Direct child of <body> — outside any transformed/animated ancestor.
    expect(backdrop.parentElement).toBe(document.body)
    // And crucially NOT inside the component's own mount point.
    expect(container.contains(backdrop)).toBe(false)
  })

  it('confirm click still fires through the portal boundary (#113)', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(<ConfirmDialog {...defaults({ onConfirm, onCancel, testId: 'pat' })} />)
    const confirm = screen.getByTestId('pat-confirm')
    // The full gesture sequence resolves on the BUTTON — the click must fire
    // the action, not bubble to the backdrop's dismiss handler.
    fireEvent.pointerDown(confirm)
    fireEvent.mouseDown(confirm)
    fireEvent.click(confirm)
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onCancel).not.toHaveBeenCalled()
  })
})
