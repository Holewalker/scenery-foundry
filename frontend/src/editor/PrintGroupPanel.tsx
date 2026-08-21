import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { createPrintGroup, deletePrintGroup, fetchPrintGroups } from '../api/client'
import { useEditorStore } from './store'

interface PrintGroupPanelProps {
  projectId: string
}

// Belongs to at most one Print Group (design D6): a single <select> replaces the prior
// assignment; "Unassigned" clears it. Calls only existing backend CRUD endpoints.
export function PrintGroupPanel({ projectId }: PrintGroupPanelProps) {
  const printGroups = useEditorStore((state) => state.printGroups)
  const setPrintGroups = useEditorStore((state) => state.setPrintGroups)
  const objects = useEditorStore((state) => state.objects)
  const selectedId = useEditorStore((state) => state.selectedId)
  const assignPrintGroup = useEditorStore((state) => state.assignPrintGroup)
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchPrintGroups(projectId)
      .then(setPrintGroups)
      .catch(() => setError('Failed to load print groups'))
  }, [projectId, setPrintGroups])

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return
    setError(null)
    try {
      const created = await createPrintGroup(projectId, trimmed)
      setPrintGroups([...useEditorStore.getState().printGroups, created])
      setName('')
    } catch {
      setError('Failed to create print group')
    }
  }

  async function handleDelete(id: string) {
    setError(null)
    try {
      await deletePrintGroup(id)
      setPrintGroups(useEditorStore.getState().printGroups.filter((group) => group.id !== id))
    } catch {
      setError('Failed to delete print group')
    }
  }

  const selectedObject = objects.find((object) => object.id === selectedId) ?? null

  return (
    <div className="print-group-panel">
      <form onSubmit={(event) => void handleCreate(event)}>
        <label>
          New print group
          <input value={name} onChange={(event) => setName(event.target.value)} />
        </label>
        <button type="submit">Create</button>
      </form>
      <ul>
        {printGroups.map((group) => (
          <li key={group.id}>
            {group.name}
            <button type="button" aria-label={`Delete ${group.name}`} onClick={() => void handleDelete(group.id)}>
              Delete
            </button>
          </li>
        ))}
      </ul>
      {selectedObject && (
        <label>
          Assign selected object to
          <select
            value={selectedObject.printGroupId ?? ''}
            onChange={(event) => assignPrintGroup(selectedObject.id, event.target.value || null)}
          >
            <option value="">Unassigned</option>
            {printGroups.map((group) => (
              <option key={group.id} value={group.id}>
                {group.name}
              </option>
            ))}
          </select>
        </label>
      )}
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
