import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SaveStatus, resolveDisplaySaveState } from './SaveStatus'

describe('resolveDisplaySaveState (pure)', () => {
  it('shows saved/unsaved purely from the dirty flag when saveState is not an active exception state', () => {
    expect(resolveDisplaySaveState(false, 'saved')).toBe('saved')
    expect(resolveDisplaySaveState(true, 'saved')).toBe('unsaved')
  })

  it('lets an active exception state (saving/retrying/offline/conflict/invalid) win over dirty', () => {
    expect(resolveDisplaySaveState(true, 'saving')).toBe('saving')
    expect(resolveDisplaySaveState(false, 'conflict')).toBe('conflict')
    expect(resolveDisplaySaveState(true, 'offline')).toBe('offline')
  })
})

describe('SaveStatus', () => {
  it('renders the correct status label for each save state', () => {
    const { rerender } = render(<SaveStatus dirty={false} saveState="saved" onReload={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('Saved')

    rerender(<SaveStatus dirty={true} saveState="saved" onReload={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes')

    rerender(<SaveStatus dirty={true} saveState="saving" onReload={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('Saving…')

    rerender(<SaveStatus dirty={true} saveState="retrying" onReload={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('Retrying')

    rerender(<SaveStatus dirty={true} saveState="offline" onReload={vi.fn()} />)
    expect(screen.getByRole('status')).toHaveTextContent('Offline')
  })

  it('renders a prominent alertdialog (not a toast) on conflict, moves focus to it, and offers only a reload action — no overwrite control', () => {
    const onReload = vi.fn()
    render(<SaveStatus dirty={true} saveState="conflict" onReload={onReload} />)

    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toBeInTheDocument()
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Reload latest scene' }))
    expect(screen.queryByRole('button', { name: /overwrite/i })).not.toBeInTheDocument()
  })

  it('calls onReload when the reload action is activated', () => {
    const onReload = vi.fn()
    render(<SaveStatus dirty={true} saveState="conflict" onReload={onReload} />)

    screen.getByRole('button', { name: 'Reload latest scene' }).click()

    expect(onReload).toHaveBeenCalledTimes(1)
  })

  it('does not render the alertdialog for any non-conflict state', () => {
    render(<SaveStatus dirty={true} saveState="invalid" errorMessage="Bad request" onReload={vi.fn()} />)

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Bad request')
  })
})
