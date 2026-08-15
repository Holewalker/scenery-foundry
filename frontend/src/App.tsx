import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import './app.css'
import { fetchAssets, fetchScene, login, saveScene } from './api/client'
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
  const toSceneDto = useEditorStore((state) => state.toSceneDto)
  const mode = useEditorStore((state) => state.mode)
  const setMode = useEditorStore((state) => state.setMode)
  const saving = useEditorStore((state) => state.loading)
  const setSaving = useEditorStore((state) => state.setLoading)
  const saveError = useEditorStore((state) => state.error)
  const setSaveError = useEditorStore((state) => state.setError)
  const dirty = useEditorStore((state) => state.dirty)

  async function handleSave() {
    if (!projectId || saving) return
    setSaving(true)
    setSaveError(null)
    try {
      const saved = await saveScene(projectId, toSceneDto())
      loadScene(saved)
    } catch {
      setSaveError('Failed to save the scene.')
    } finally {
      setSaving(false)
    }
  }

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
        <div className="login-card" data-testid="login-card">
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
        </div>
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

  const saveStateLabel = saveError ? 'Save failed' : saving ? 'Saving…' : dirty ? 'Unsaved changes' : 'Saved'

  return (
    <div className="editor-shell">
      <header className="app-bar">
        <h1>Scenery Foundry</h1>
        <p className="save-state" role="status">
          {saveStateLabel}
        </p>
      </header>
      <aside className="panel">
        <AssetCatalog assets={assets} />
      </aside>
      <section className="viewport">
        <EditorCanvas projectId={projectId} />
      </section>
      <footer className="editor-toolbar">
        <button type="button" aria-pressed={mode === 'translate'} onClick={() => setMode('translate')}>
          <svg aria-hidden="true" focusable="false" width="16" height="16" viewBox="0 0 16 16">
            <path d="M8 1v14M1 8h14M4 4L1 8l3 4M12 4l3 4-3 4M4 12l4 3 4-3M4 4l4-3 4 3" />
          </svg>
          Move
        </button>
        <button type="button" aria-pressed={mode === 'rotate'} onClick={() => setMode('rotate')}>
          <svg aria-hidden="true" focusable="false" width="16" height="16" viewBox="0 0 16 16">
            <path d="M13 8A5 5 0 1 1 8 3" />
            <path d="M8 1l3 2-3 2" />
          </svg>
          Rotate
        </button>
        <button type="button" onClick={handleSave} disabled={saving}>
          <svg aria-hidden="true" focusable="false" width="16" height="16" viewBox="0 0 16 16">
            <path d="M2 2h9l3 3v9H2z" />
            <path d="M5 2v4h5V2M4 14v-5h8v5" />
          </svg>
          {saving ? 'Saving…' : 'Save'}
        </button>
      </footer>
      {(error ?? saveError) && <p role="alert">{error ?? saveError}</p>}
    </div>
  )
}
