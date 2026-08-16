import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './editor/store'

const loginMock = vi.fn()
const fetchAssetsMock = vi.fn()
const fetchSceneMock = vi.fn()
const saveSceneMock = vi.fn()
const confirmMock = vi.spyOn(window, 'confirm')
vi.mock('./api/client', () => ({
  login: (...args: unknown[]) => loginMock(...args),
  fetchAssets: (...args: unknown[]) => fetchAssetsMock(...args),
  fetchScene: (...args: unknown[]) => fetchSceneMock(...args),
  saveScene: (...args: unknown[]) => saveSceneMock(...args),
}))
vi.mock('./editor/EditorCanvas', () => ({
  EditorCanvas: () => <div data-testid="editor-canvas" />,
}))

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
    await waitFor(() => expect(fetchAssetsMock).toHaveBeenCalledWith('project-1'))
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

  it('saves the current scene and reloads it, guarding against duplicate concurrent submissions', async () => {
    let resolveSave: (value: { objects: [] }) => void = () => {}
    saveSceneMock.mockReturnValue(new Promise((resolve) => { resolveSave = resolve }))
    await signIn()

    const saveButton = screen.getByRole('button', { name: 'Save' })
    fireEvent.click(saveButton)
    fireEvent.click(saveButton)

    expect(saveSceneMock).toHaveBeenCalledTimes(1)
    expect(saveButton).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('Saving…')

    resolveSave({ objects: [] })
    await waitFor(() => expect(saveButton).not.toBeDisabled())
    expect(screen.getByRole('status')).toHaveTextContent('Saved')
  })

  it('shows an error message when saving the scene fails', async () => {
    saveSceneMock.mockRejectedValue(new Error('save failed'))
    await signIn()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Failed to save the scene.'))
    expect(screen.getByRole('status')).toHaveTextContent('Save failed')
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

  it('places the asset catalog and viewport inside distinct panel containers', async () => {
    await signIn()

    const catalogPanel = document.querySelector('aside.panel')
    const viewportPanel = document.querySelector('section.viewport')

    expect(catalogPanel).not.toBeNull()
    expect(viewportPanel).not.toBeNull()
    expect(within(catalogPanel as HTMLElement).getByRole('list')).toBeInTheDocument()
    expect(within(viewportPanel as HTMLElement).getByTestId('editor-canvas')).toBeInTheDocument()
    expect(screen.getAllByRole('button').map((button) => button.textContent)).toEqual(['Move', 'Rotate', 'Snap', 'Delete', 'Save'])
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

    await waitFor(() => expect(saveSceneMock).toHaveBeenCalledWith('project-1', { objects: [] }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Saved'))
    expect(screen.getByRole('status')).not.toHaveTextContent('Unsaved changes')
  })
})
