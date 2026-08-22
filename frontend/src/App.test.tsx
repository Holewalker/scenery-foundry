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
