import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './editor/store'

const loginMock = vi.fn()
const fetchAssetsMock = vi.fn()
const fetchSceneMock = vi.fn()
const saveSceneMock = vi.fn()
const uploadAssetMock = vi.fn()
// PrintGroupPanel/ExportPanel are mounted by App.tsx (Phase 4 wiring) and call these on render.
const fetchPrintGroupsMock = vi.fn()
const createPrintGroupMock = vi.fn()
const deletePrintGroupMock = vi.fn()
const captureCombinedExportMock = vi.fn()
const fetchCombinedExportStatusMock = vi.fn()
const confirmMock = vi.spyOn(window, 'confirm')
vi.mock('./api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./api/client')>()
  return {
    ...actual, // keeps the real `ApiError` class usable for constructing rejections in tests
    login: (...args: unknown[]) => loginMock(...args),
    fetchAssets: (...args: unknown[]) => fetchAssetsMock(...args),
    fetchScene: (...args: unknown[]) => fetchSceneMock(...args),
    saveScene: (...args: unknown[]) => saveSceneMock(...args),
    uploadAsset: (...args: unknown[]) => uploadAssetMock(...args),
    fetchPrintGroups: (...args: unknown[]) => fetchPrintGroupsMock(...args),
    createPrintGroup: (...args: unknown[]) => createPrintGroupMock(...args),
    deletePrintGroup: (...args: unknown[]) => deletePrintGroupMock(...args),
    captureCombinedExport: (...args: unknown[]) => captureCombinedExportMock(...args),
    fetchCombinedExportStatus: (...args: unknown[]) => fetchCombinedExportStatusMock(...args),
  }
})
vi.mock('./editor/EditorCanvas', () => ({
  EditorCanvas: () => <div data-testid="editor-canvas" />,
}))
vi.mock('./editor/ProjectPicker', () => ({
  ProjectPicker: () => <div data-testid="project-picker" />,
}))

import { ApiError } from './api/client'
import { App } from './App'

async function signIn() {
  render(<App />)
  fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'owner@example.com' } })
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } })
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
  await waitFor(() => expect(screen.getByTestId('editor-canvas')).toBeInTheDocument())
}

beforeEach(() => {
  resetEditorStore()
  loginMock.mockReset().mockResolvedValue(undefined)
  fetchAssetsMock.mockReset().mockResolvedValue([])
  fetchSceneMock.mockReset().mockResolvedValue({ objects: [] })
  saveSceneMock.mockReset().mockResolvedValue({ objects: [] })
  uploadAssetMock.mockReset()
  fetchPrintGroupsMock.mockReset().mockResolvedValue([])
  createPrintGroupMock.mockReset()
  deletePrintGroupMock.mockReset()
  captureCombinedExportMock.mockReset()
  fetchCombinedExportStatusMock.mockReset()
  confirmMock.mockReset().mockReturnValue(true)
  window.history.replaceState({}, '', '/?project=project-1')
})

describe('App', () => {
  it('renders a login form before authentication', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Scenery Foundry' })).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
  })

  it('renders the login form inside a token-styled card while preserving accessible names', () => {
    render(<App />)

    const card = screen.getByTestId('login-card')

    expect(within(card).getByRole('heading', { name: 'Scenery Foundry' })).toBeInTheDocument()
    expect(within(card).getByLabelText('Email')).toBeInTheDocument()
    expect(within(card).getByLabelText('Password')).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: 'Sign in' })).toBeInTheDocument()
  })

  it('shows the project picker instead of the editor when authenticated with no ?project= in the URL', async () => {
    window.history.replaceState({}, '', '/')
    loginMock.mockResolvedValue(undefined)
    render(<App />)

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'owner@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(screen.getByTestId('project-picker')).toBeInTheDocument())
    expect(screen.queryByTestId('editor-canvas')).not.toBeInTheDocument()
    expect(fetchAssetsMock).not.toHaveBeenCalled()
  })

  it('loads the project catalog and scene and shows the editor after a successful login', async () => {
    loginMock.mockResolvedValue(undefined)
    render(<App />)

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'owner@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(screen.getByTestId('editor-canvas')).toBeInTheDocument())
    expect(loginMock).toHaveBeenCalledWith('owner@example.com', 'secret')
    await waitFor(() => expect(fetchAssetsMock).toHaveBeenCalledWith())
    await waitFor(() => expect(fetchSceneMock).toHaveBeenCalledWith('project-1'))
  })

  it('shows an error message when login is rejected', async () => {
    loginMock.mockRejectedValue(new Error('login failed'))
    render(<App />)

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'owner@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Invalid email or password.'))
  })

  it('saves a dirty scene via a manual Save and reloads it, guarding against duplicate concurrent submissions', async () => {
    let resolveSave: (value: { version: number; objects: [] }) => void = () => {}
    saveSceneMock.mockReturnValue(new Promise((resolve) => { resolveSave = resolve }))
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    const saveButton = screen.getByRole('button', { name: 'Save' })
    fireEvent.click(saveButton)
    fireEvent.click(saveButton)

    expect(saveSceneMock).toHaveBeenCalledTimes(1)
    expect(saveButton).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Saving…')

    resolveSave({ version: 1, objects: [] })
    await waitFor(() => expect(saveButton).not.toBeDisabled())
    expect(screen.getByRole('status')).toHaveTextContent('Saved')
    expect(useEditorStore.getState().sceneVersion).toBe(1)
  })

  // Network/5xx failures must not surface as an immediate hard failure (ADR-0007 / spec
  // "Error-Class-Differentiated Save Handling"): they enter a bounded-retry Retrying state.
  it('shows a Retrying state, not an immediate failure message, when a manual save fails due to a network error', async () => {
    saveSceneMock.mockRejectedValue(new Error('network error'))
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Retrying'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('surfaces a validation error message and stops further autosave attempts on a non-409 4xx save response', async () => {
    saveSceneMock.mockRejectedValue(new ApiError(422, 'INVALID_SCENE', 'too many objects'))
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Save failed'))
    expect(screen.getByRole('alert')).toHaveTextContent(/could not be saved/i)
  })

  // Codex fix (PR #54, finding 1): a user who fixes an invalid scene must be able to persist it
  // without a full page refresh — Retry clears the suspension and flushes the corrected edits.
  it('lets the user retry and persist a scene after fixing a validation failure', async () => {
    saveSceneMock.mockRejectedValueOnce(new ApiError(422, 'INVALID_SCENE', 'too many objects'))
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Save failed'))
    expect(screen.getByRole('alert')).toHaveTextContent(/could not be saved/i)

    // Manual Save is still a no-op while suspended: only Retry lifts the suspension.
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(saveSceneMock).toHaveBeenCalledTimes(1)

    // The user "fixes" the scene (a further local edit), then retries.
    act(() => {
      useEditorStore.getState().insert('asset-b')
    })
    saveSceneMock.mockResolvedValueOnce({ version: 1, objects: [] })
    fireEvent.click(screen.getByRole('button', { name: 'Retry save' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved'))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(saveSceneMock).toHaveBeenCalledTimes(2)

    // Suspension fully lifted: a later manual save reaches the server again.
    act(() => {
      useEditorStore.getState().insert('asset-c')
    })
    saveSceneMock.mockResolvedValueOnce({ version: 2, objects: [] })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(saveSceneMock).toHaveBeenCalledTimes(3))
  })

  // Codex fix (PR #54, finding 2): state.error is also populated by EditorCanvas/PrintGroupPanel
  // for unrelated geometry/print-group failures and must stay visible outside the invalid state.
  it('shows a general store error during normal saved/unsaved states without duplicating the invalid-state alert', async () => {
    await signIn()

    act(() => {
      useEditorStore.getState().setError('Failed to load object geometry.')
    })
    expect(screen.getByRole('alert')).toHaveTextContent('Failed to load object geometry.')
    expect(screen.getAllByRole('alert')).toHaveLength(1)

    // Once a save actually fails validation, the invalid-state alert takes over and the general
    // banner (which would otherwise show the very same store field) steps aside — never both.
    saveSceneMock.mockRejectedValueOnce(new ApiError(422, 'INVALID_SCENE', 'too many objects'))
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Save failed'))
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.getByRole('alert')).toHaveTextContent(/could not be saved/i)
  })

  // Step 3: geometry failures are now scoped per object (EditorCanvas/store.objectGeometryErrors)
  // instead of the old generic global-banner text — this proves the new path and that the global
  // `error`/role="alert" banner is never touched by it.
  it('shows a scoped geometry error with a Retry control for the selected object, and leaves the global error banner untouched', async () => {
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })
    const selectedId = useEditorStore.getState().selectedId as number

    act(() => {
      useEditorStore.getState().setObjectGeometryError(selectedId, 'Failed to load object geometry.')
    })

    expect(screen.getByRole('alert')).toHaveTextContent('Failed to load object geometry.')
    expect(useEditorStore.getState().error).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(useEditorStore.getState().geometryRetryTick[selectedId]).toBe(1)
  })

  it('never shows a Retry control when the selected object has no geometry error', async () => {
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })

  it('on a 409 conflict, renders a prominent alertdialog with only a reload action; reload refetches the scene and resumes normal saving', async () => {
    saveSceneMock.mockRejectedValueOnce(new ApiError(409, 'SCENE_VERSION_CONFLICT', 'stale version'))
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    const dialog = await screen.findByRole('alertdialog')
    const reloadButton = within(dialog).getByRole('button', { name: 'Reload latest scene' })
    expect(document.activeElement).toBe(reloadButton)
    expect(screen.queryByRole('button', { name: /overwrite/i })).not.toBeInTheDocument()

    fetchSceneMock.mockResolvedValueOnce({ version: 9, objects: [] })
    fireEvent.click(reloadButton)

    await waitFor(() => expect(fetchSceneMock).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(useEditorStore.getState().sceneVersion).toBe(9)
    expect(screen.getByRole('status')).toHaveTextContent('Saved')

    // Autosave resumed: a fresh edit followed by a manual Save reaches the server again.
    saveSceneMock.mockResolvedValueOnce({ version: 10, objects: [] })
    act(() => {
      useEditorStore.getState().insert('asset-b')
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(saveSceneMock).toHaveBeenCalledTimes(2))
  })

  // Codex fix (PR #54, finding 3): aria-modal alone does not stop keyboard/pointer users from
  // reaching the editor behind the conflict dialog — the rest of the app must be made inert.
  it('marks the rest of the editor inert while the conflict dialog is open, and interactive again once it closes', async () => {
    saveSceneMock.mockRejectedValueOnce(new ApiError(409, 'SCENE_VERSION_CONFLICT', 'stale version'))
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByRole('alertdialog')

    const deleteButton = screen.getByRole('button', { name: 'Delete' })
    expect(deleteButton.closest('[inert]')).not.toBeNull()

    fetchSceneMock.mockResolvedValueOnce({ version: 9, objects: [] })
    fireEvent.click(screen.getByRole('button', { name: 'Reload latest scene' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(deleteButton.closest('[inert]')).toBeNull()
  })

  // Codex fix (PR #54, finding 4): a stale failed-reload message must not survive a later
  // successful reload.
  it('clears a failed-reload error once a subsequent reload attempt succeeds', async () => {
    saveSceneMock.mockRejectedValueOnce(new ApiError(409, 'SCENE_VERSION_CONFLICT', 'stale version'))
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    const reloadButton = await screen.findByRole('button', { name: 'Reload latest scene' })

    fetchSceneMock.mockRejectedValueOnce(new Error('network error'))
    fireEvent.click(reloadButton)
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Failed to reload the scene.'))

    fetchSceneMock.mockResolvedValueOnce({ version: 9, objects: [] })
    fireEvent.click(reloadButton)

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('warns before unload while the scene has unsaved changes, and stays silent once saved', async () => {
    await signIn()

    const cleanEvent = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(cleanEvent)
    expect(cleanEvent.defaultPrevented).toBe(false)

    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    const dirtyEvent = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(dirtyEvent)
    expect(dirtyEvent.defaultPrevented).toBe(true)
  })

  it('wires a mode switch control to the active transform mode', async () => {
    await signIn()

    expect(useEditorStore.getState().mode).toBe('translate')
    fireEvent.click(screen.getByRole('button', { name: 'Rotate' }))
    expect(useEditorStore.getState().mode).toBe('rotate')
    fireEvent.click(screen.getByRole('button', { name: 'Move' }))
    expect(useEditorStore.getState().mode).toBe('translate')
  })

  it('shows a clean save state in the header before any edit, and reflects unsaved changes after one', async () => {
    await signIn()

    expect(screen.getByRole('status')).toHaveTextContent('Saved')

    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes')
    expect(screen.getByRole('button', { name: 'Save' })).toBeInTheDocument()
  })

  it('augments toolbar buttons with hidden decorative icons without changing their accessible names or text', async () => {
    await signIn()

    for (const name of ['Move', 'Rotate', 'Snap', 'Delete', 'Save']) {
      const button = screen.getByRole('button', { name })
      const icon = button.querySelector('svg[aria-hidden="true"]')
      expect(icon).not.toBeNull()
      expect(button.querySelector('title')).toBeNull()
      expect(button.querySelector('desc')).toBeNull()
    }
  })

  it('mounts PrintGroupPanel with the current project, and its Pieces Export link per group with ExportPanel (final Phase 4 wiring)', async () => {
    fetchPrintGroupsMock.mockResolvedValue([{ id: 'group-1', name: 'Batch 1' }])
    await signIn()

    expect(fetchPrintGroupsMock).toHaveBeenCalledWith('project-1')
    await waitFor(() => expect(screen.getByText('Batch 1')).toBeInTheDocument())
    const link = screen.getByRole('link', { name: /download pieces/i })
    expect(link).toHaveAttribute('href', '/api/print-groups/group-1/pieces-export')
    expect(screen.getByRole('button', { name: 'Start combined export' })).toBeInTheDocument()
  })

  it('places the asset catalog and viewport inside distinct panel containers', async () => {
    await signIn()

    const catalogPanel = document.querySelector('aside.panel')
    const viewportPanel = document.querySelector('section.viewport')

    expect(catalogPanel).not.toBeNull()
    expect(viewportPanel).not.toBeNull()
    // Phase 4 wiring added PrintGroupPanel's own (empty) list alongside the asset catalog's.
    expect(within(catalogPanel as HTMLElement).getAllByRole('list')).toHaveLength(2)
    expect(within(viewportPanel as HTMLElement).getByTestId('editor-canvas')).toBeInTheDocument()
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual([
      'Create', // PrintGroupPanel's "New print group" form
      'Move',
      'Rotate',
      'Snap',
      'Delete',
      'Save',
    ])
  })

  it('toggles snap on and off via the Snap button', async () => {
    await signIn()

    const snapButton = screen.getByRole('button', { name: 'Snap' })
    expect(snapButton).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(snapButton)
    expect(useEditorStore.getState().snapEnabled).toBe(true)
    expect(snapButton).toHaveAttribute('aria-pressed', 'true')

    fireEvent.click(snapButton)
    expect(useEditorStore.getState().snapEnabled).toBe(false)
    expect(snapButton).toHaveAttribute('aria-pressed', 'false')
  })

  it('disables the Delete button when no object is selected', async () => {
    await signIn()

    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled()
  })

  it('leaves state unchanged when a delete is cancelled', async () => {
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })
    const selectedId = useEditorStore.getState().selectedId
    confirmMock.mockReturnValue(false)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(useEditorStore.getState().objects).toHaveLength(1)
    expect(useEditorStore.getState().selectedId).toBe(selectedId)
    expect(useEditorStore.getState().dirty).toBe(true)
  })

  it('removes the selected object when a delete is confirmed', async () => {
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })
    confirmMock.mockReturnValue(true)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    expect(confirmMock).toHaveBeenCalledTimes(1)
    expect(useEditorStore.getState().objects).toHaveLength(0)
    expect(useEditorStore.getState().selectedId).toBeNull()
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes')
  })

  it('enables the Delete button when an object is selected', async () => {
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })

    expect(screen.getByRole('button', { name: 'Delete' })).not.toBeDisabled()
  })

  it('persists a confirmed delete once the user clicks Save', async () => {
    await signIn()
    act(() => {
      useEditorStore.getState().insert('asset-a')
    })
    confirmMock.mockReturnValue(true)

    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

    expect(useEditorStore.getState().objects).toHaveLength(0)
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes')

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(saveSceneMock).toHaveBeenCalledWith('project-1', { objects: [], version: null }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved'))
    expect(screen.getByRole('status')).not.toHaveTextContent('Unsaved changes')
  })
})
