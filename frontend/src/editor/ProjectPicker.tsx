import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { createProject, fetchProjects } from '../api/client'
import { labelWithFallback } from './identity'
import type { ProjectSummary } from './store'

// `App`'s projectId is read once at mount via `useState(readProjectId)` and never re-derived, so
// selecting/creating a project here must cause a real navigation (full reload), not in-place SPA
// routing — that's the only way App picks up the new ?project=<id>.
function navigateToProject(id: string): void {
  window.location.href = `${window.location.pathname}?project=${id}`
}

export function ProjectPicker() {
  const [projects, setProjects] = useState<ProjectSummary[]>([])
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchProjects()
      .then(setProjects)
      .catch(() => setError('Failed to load your projects.'))
  }, [])

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return
    setError(null)
    try {
      const created = await createProject(trimmed)
      navigateToProject(created.id)
    } catch {
      setError('Failed to create the project.')
    }
  }

  return (
    <div className="project-picker">
      <h1>Select a project</h1>
      <ul>
        {projects.map((project) => (
          <li key={project.id}>
            <button type="button" onClick={() => navigateToProject(project.id)}>
              {labelWithFallback(project.name, project.id, 'Project')}
            </button>
          </li>
        ))}
      </ul>
      <form onSubmit={(event) => void handleCreate(event)}>
        <label>
          New project
          <input value={name} onChange={(event) => setName(event.target.value)} />
        </label>
        <button type="submit">Create</button>
      </form>
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
