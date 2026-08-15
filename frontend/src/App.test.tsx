import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './editor/store'

const loginMock = vi.fn()
const fetchAssetsMock = vi.fn()
const fetchSceneMock = vi.fn()
const saveSceneMock = vi.fn()
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
  window.history.replaceState({}, '', '/?project=project-1')
})

describe('App', () => {
  it('renders a login form before authentication', () => {
    render(<App />)

    expect(screen.getByRole('heading', { name: 'Scenery Foundry' })).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toBeInTheDocument()
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

    resolveSave({ objects: [] })
    await waitFor(() => expect(saveButton).not.toBeDisabled())
  })

  it('shows an error message when saving the scene fails', async () => {
    saveSceneMock.mockRejectedValue(new Error('save failed'))
    await signIn()

    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Failed to save the scene.'))
  })

  it('wires a mode switch control to the active transform mode', async () => {
    await signIn()

    expect(useEditorStore.getState().mode).toBe('translate')
    fireEvent.click(screen.getByRole('button', { name: 'Rotate' }))
    expect(useEditorStore.getState().mode).toBe('rotate')
    fireEvent.click(screen.getByRole('button', { name: 'Move' }))
    expect(useEditorStore.getState().mode).toBe('translate')
  })
})
