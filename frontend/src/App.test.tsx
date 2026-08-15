import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore } from './editor/store'

const loginMock = vi.fn()
const fetchAssetsMock = vi.fn()
const fetchSceneMock = vi.fn()
vi.mock('./api/client', () => ({
  login: (...args: unknown[]) => loginMock(...args),
  fetchAssets: (...args: unknown[]) => fetchAssetsMock(...args),
  fetchScene: (...args: unknown[]) => fetchSceneMock(...args),
}))
vi.mock('./editor/EditorCanvas', () => ({
  EditorCanvas: () => <div data-testid="editor-canvas" />,
}))

import { App } from './App'

beforeEach(() => {
  resetEditorStore()
  loginMock.mockReset()
  fetchAssetsMock.mockReset().mockResolvedValue([])
  fetchSceneMock.mockReset().mockResolvedValue({ objects: [] })
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
    expect(fetchAssetsMock).toHaveBeenCalledWith('project-1')
    expect(fetchSceneMock).toHaveBeenCalledWith('project-1')
  })

  it('shows an error message when login is rejected', async () => {
    loginMock.mockRejectedValue(new Error('login failed'))
    render(<App />)

    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'owner@example.com' } })
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'wrong' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Invalid email or password.'))
  })
})
