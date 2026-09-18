import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ProjectPicker } from './ProjectPicker'

const fetchProjectsMock = vi.fn()
const createProjectMock = vi.fn()
vi.mock('../api/client', () => ({
  fetchProjects: (...args: unknown[]) => fetchProjectsMock(...args),
  createProject: (...args: unknown[]) => createProjectMock(...args),
}))

const originalLocation = window.location

beforeEach(() => {
  fetchProjectsMock.mockReset().mockResolvedValue([])
  createProjectMock.mockReset()
  Object.defineProperty(window, 'location', {
    configurable: true,
    writable: true,
    value: { ...originalLocation, href: '', pathname: '/', search: '' },
  })
})

describe('ProjectPicker', () => {
  it('fetches and renders the owner projects, showing the name when present', async () => {
    fetchProjectsMock.mockResolvedValue([{ id: 'project-1', name: 'My Project' }])

    render(<ProjectPicker />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'My Project' })).toBeInTheDocument())
  })

  it('shows the short-id fallback label for a project with no name', async () => {
    fetchProjectsMock.mockResolvedValue([{ id: 'a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d', name: null }])

    render(<ProjectPicker />)

    await waitFor(() => expect(screen.getByRole('button', { name: 'Project a1b2c3d4' })).toBeInTheDocument())
  })

  it('navigates to ?project=<id> when a project is selected', async () => {
    fetchProjectsMock.mockResolvedValue([{ id: 'project-1', name: 'My Project' }])

    render(<ProjectPicker />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'My Project' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: 'My Project' }))

    expect(window.location.href).toBe('/?project=project-1')
  })

  it('shows a local, scoped error message when fetching projects fails', async () => {
    fetchProjectsMock.mockRejectedValue(new Error('network error'))

    render(<ProjectPicker />)

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/failed to load/i))
  })

  it('creates a project via the form and navigates to it', async () => {
    fetchProjectsMock.mockResolvedValue([])
    createProjectMock.mockResolvedValue({ id: 'project-new', name: 'Fresh Project' })

    render(<ProjectPicker />)
    await waitFor(() => expect(fetchProjectsMock).toHaveBeenCalled())

    fireEvent.change(screen.getByLabelText(/new project/i), { target: { value: 'Fresh Project' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(createProjectMock).toHaveBeenCalledWith('Fresh Project'))
    await waitFor(() => expect(window.location.href).toBe('/?project=project-new'))
  })

  it('shows a local, scoped error message when project creation fails, without navigating', async () => {
    fetchProjectsMock.mockResolvedValue([])
    createProjectMock.mockRejectedValue(new Error('failed to create project'))

    render(<ProjectPicker />)
    await waitFor(() => expect(fetchProjectsMock).toHaveBeenCalled())

    fireEvent.change(screen.getByLabelText(/new project/i), { target: { value: 'Fresh Project' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(/failed to create/i))
    expect(window.location.href).toBe('')
  })

  it('does not submit the create form when the name is blank', async () => {
    fetchProjectsMock.mockResolvedValue([])

    render(<ProjectPicker />)
    await waitFor(() => expect(fetchProjectsMock).toHaveBeenCalled())

    fireEvent.click(screen.getByRole('button', { name: 'Create' }))

    expect(createProjectMock).not.toHaveBeenCalled()
  })
})
