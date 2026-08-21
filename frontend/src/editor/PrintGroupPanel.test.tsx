import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './store'
import { PrintGroupPanel } from './PrintGroupPanel'

const fetchPrintGroupsMock = vi.fn()
const createPrintGroupMock = vi.fn()
const deletePrintGroupMock = vi.fn()
vi.mock('../api/client', () => ({
  fetchPrintGroups: (...args: unknown[]) => fetchPrintGroupsMock(...args),
  createPrintGroup: (...args: unknown[]) => createPrintGroupMock(...args),
  deletePrintGroup: (...args: unknown[]) => deletePrintGroupMock(...args),
}))

beforeEach(() => {
  resetEditorStore()
  fetchPrintGroupsMock.mockReset()
  createPrintGroupMock.mockReset()
  deletePrintGroupMock.mockReset()
  fetchPrintGroupsMock.mockResolvedValue([])
})

it('loads/lists groups, creates one (ignoring a blank name), then deletes one', async () => {
  fetchPrintGroupsMock.mockResolvedValue([{ id: 'group-1', name: 'Group 1' }])
  createPrintGroupMock.mockResolvedValue({ id: 'group-new', name: 'New Group' })
  deletePrintGroupMock.mockResolvedValue(undefined)

  render(<PrintGroupPanel projectId="project-1" />)
  await waitFor(() => expect(screen.getByText('Group 1')).toBeInTheDocument())
  expect(fetchPrintGroupsMock).toHaveBeenCalledWith('project-1')

  fireEvent.change(screen.getByLabelText('New print group'), { target: { value: '   ' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create' }))
  expect(createPrintGroupMock).not.toHaveBeenCalled()

  fireEvent.change(screen.getByLabelText('New print group'), { target: { value: 'New Group' } })
  fireEvent.click(screen.getByRole('button', { name: 'Create' }))
  await waitFor(() => expect(screen.getByText('New Group')).toBeInTheDocument())
  expect(createPrintGroupMock).toHaveBeenCalledWith('project-1', 'New Group')
  expect(useEditorStore.getState().printGroups).toEqual([
    { id: 'group-1', name: 'Group 1' },
    { id: 'group-new', name: 'New Group' },
  ])

  fireEvent.click(screen.getByRole('button', { name: 'Delete Group 1' }))
  await waitFor(() => expect(screen.queryByText('Group 1')).not.toBeInTheDocument())
  expect(deletePrintGroupMock).toHaveBeenCalledWith('group-1')
})

it('shows no assignment control until an object is selected (design D6), then assigns/reassigns/unassigns it', async () => {
  fetchPrintGroupsMock.mockResolvedValue([
    { id: 'group-1', name: 'Group 1' },
    { id: 'group-2', name: 'Group 2' },
  ])
  render(<PrintGroupPanel projectId="project-1" />)
  await waitFor(() => expect(screen.getByText('Group 1')).toBeInTheDocument())
  expect(screen.queryByLabelText('Assign selected object to')).not.toBeInTheDocument()

  let id = 0
  act(() => {
    id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)
  })
  const select = await screen.findByLabelText('Assign selected object to')

  fireEvent.change(select, { target: { value: 'group-1' } })
  expect(useEditorStore.getState().objects.find((o) => o.id === id)?.printGroupId).toBe('group-1')
  fireEvent.change(select, { target: { value: 'group-2' } })
  expect(useEditorStore.getState().objects.find((o) => o.id === id)?.printGroupId).toBe('group-2')
  fireEvent.change(select, { target: { value: '' } })
  expect(useEditorStore.getState().objects.find((o) => o.id === id)?.printGroupId).toBeNull()
})
