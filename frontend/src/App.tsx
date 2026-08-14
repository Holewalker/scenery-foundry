import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import './app.css'
import { fetchAssets, fetchScene, login } from './api/client'
import { AssetCatalog } from './editor/AssetCatalog'
import { EditorCanvas } from './editor/EditorCanvas'
import { useEditorStore } from './editor/store'

function readProjectId(): string | null {
  return new URLSearchParams(window.location.search).get('project')
}

export function App() {
  const [authenticated, setAuthenticated] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [projectId] = useState(readProjectId)
  const assets = useEditorStore((state) => state.assets)
  const setAssets = useEditorStore((state) => state.setAssets)
  const loadScene = useEditorStore((state) => state.loadScene)

  useEffect(() => {
    if (!authenticated || !projectId) return
    Promise.all([fetchAssets(projectId), fetchScene(projectId)])
      .then(([fetchedAssets, scene]) => {
        setAssets(fetchedAssets)
        loadScene(scene)
      })
      .catch(() => setError('Failed to load the project.'))
  }, [authenticated, projectId, setAssets, loadScene])

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    try {
      await login(email, password)
      setAuthenticated(true)
    } catch {
      setError('Invalid email or password.')
    }
  }

  if (!authenticated) {
    return (
      <main>
        <p className="eyebrow">3D workspace</p>
        <h1>Scenery Foundry</h1>
        <form onSubmit={handleSubmit}>
          <label>
            Email
            <input value={email} onChange={(event) => setEmail(event.target.value)} type="email" required />
          </label>
          <label>
            Password
            <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" required />
          </label>
          <button type="submit">Sign in</button>
          {error && <p role="alert">{error}</p>}
        </form>
      </main>
    )
  }

  if (!projectId) {
    return (
      <main>
        <p role="alert">Add ?project=&lt;id&gt; to the URL to open a project.</p>
      </main>
    )
  }

  return (
    <div className="editor-shell">
      <AssetCatalog assets={assets} />
      <EditorCanvas projectId={projectId} />
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
