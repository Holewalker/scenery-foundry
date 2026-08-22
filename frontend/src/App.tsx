import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import './app.css'
import { fetchAssets, fetchScene, login, saveScene } from './api/client'
import { AssetCatalog } from './editor/AssetCatalog'
import { AssetUpload } from './editor/AssetUpload'
import type { AutosaveScheduler } from './editor/autosave'
import { createAutosaveScheduler } from './editor/autosave'
import { EditorCanvas } from './editor/EditorCanvas'
import { PrintGroupPanel } from './editor/PrintGroupPanel'
import { SaveStatus } from './editor/SaveStatus'
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
  const mode = useEditorStore((state) => state.mode)
  const setMode = useEditorStore((state) => state.setMode)
  const saveState = useEditorStore((state) => state.saveState)
  const saveError = useEditorStore((state) => state.error)
  const setSaveError = useEditorStore((state) => state.setError)
  const dirty = useEditorStore((state) => state.dirty)
  const selectedId = useEditorStore((state) => state.selectedId)
  const remove = useEditorStore((state) => state.remove)
  const snapEnabled = useEditorStore((state) => state.snapEnabled)
  const toggleSnap = useEditorStore((state) => state.toggleSnap)
  const schedulerRef = useRef<AutosaveScheduler | null>(null)

  const saving = saveState === 'saving'
  const saveDisabled = saveState === 'saving' || saveState === 'conflict'

  async function handleSave() {
    if (!projectId) return
    await schedulerRef.current?.flushNow()
  }

  function handleDelete() {
    if (selectedId === null) return
    if (!window.confirm('Delete the selected object?')) return
    remove(selectedId)
  }

  async function handleReloadAfterConflict() {
    if (!projectId) return
    try {
      const scene = await fetchScene(projectId)
      loadScene(scene)
      schedulerRef.current?.reload()
    } catch {
      setError('Failed to reload the scene.')
    }
  }

  useEffect(() => {
    if (!authenticated || !projectId) return
    Promise.all([fetchAssets(), fetchScene(projectId)])
      .then(([fetchedAssets, scene]) => {
        setAssets(fetchedAssets)
        loadScene(scene)
      })
      .catch(() => setError('Failed to load the project.'))
  }, [authenticated, projectId, setAssets, loadScene])

  // Mounts the autosave scheduler (ADR-0007): a store subscription notifies it on every
  // scene-mutating action (revision bump) so it stays framework-free per D5, and it is torn
  // down on unmount / when the project changes.
  useEffect(() => {
    if (!authenticated || !projectId) return
    const scheduler = createAutosaveScheduler({
      projectId,
      store: {
        getRevision: () => useEditorStore.getState().revision,
        getSceneVersion: () => useEditorStore.getState().sceneVersion,
        isDirty: () => useEditorStore.getState().dirty,
        toSceneDto: () => useEditorStore.getState().toSceneDto(),
        markSaved: (version, revisionAtSend) => useEditorStore.getState().markSaved(version, revisionAtSend),
        setSaveState: (state) => useEditorStore.getState().setSaveState(state),
        setSaveError: (message) => useEditorStore.getState().setError(message),
      },
      saveScene,
    })
    schedulerRef.current = scheduler

    let previousRevision = useEditorStore.getState().revision
    const unsubscribe = useEditorStore.subscribe((state) => {
      if (state.revision !== previousRevision) {
        previousRevision = state.revision
        scheduler.notifyEdit()
      }
    })

    function handleOnline() {
      scheduler.resume()
    }
    window.addEventListener('online', handleOnline)

    return () => {
      unsubscribe()
      window.removeEventListener('online', handleOnline)
      scheduler.stop()
      schedulerRef.current = null
    }
  }, [authenticated, projectId])

  // Warns before an unload/navigation while the scene has unsaved changes (spec: "beforeunload
  // Guard While Dirty"); silent once the scene is clean.
  useEffect(() => {
    function handleBeforeUnload(event: BeforeUnloadEvent) {
      if (!dirty) return
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', handleBeforeUnload)
    return () => window.removeEventListener('beforeunload', handleBeforeUnload)
  }, [dirty])

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

  return (
    <div className="editor-shell">
      <header className="app-bar">
        <h1>Scenery Foundry</h1>
        <SaveStatus dirty={dirty} saveState={saveState} errorMessage={saveError} onReload={handleReloadAfterConflict} />
      </header>
      <aside className="panel">
        <AssetUpload />
        <AssetCatalog assets={assets} />
        <PrintGroupPanel projectId={projectId} />
      </aside>
      <section className="viewport">
        <EditorCanvas />
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
        <button type="button" aria-pressed={snapEnabled} onClick={toggleSnap}>
          <svg aria-hidden="true" focusable="false" width="16" height="16" viewBox="0 0 16 16">
            <path d="M1 5h14M1 11h14M5 1v14M11 1v14" />
          </svg>
          Snap
        </button>
        <button type="button" className="danger" onClick={handleDelete} disabled={selectedId === null}>
          <svg aria-hidden="true" focusable="false" width="16" height="16" viewBox="0 0 16 16">
            <path d="M2 4h12M6 4V2h4v2M4 4l1 10h6l1-10" />
          </svg>
          Delete
        </button>
        <button type="button" onClick={handleSave} disabled={saveDisabled}>
          <svg aria-hidden="true" focusable="false" width="16" height="16" viewBox="0 0 16 16">
            <path d="M2 2h9l3 3v9H2z" />
            <path d="M5 2v4h5V2M4 14v-5h8v5" />
          </svg>
          {saving ? 'Saving…' : 'Save'}
        </button>
      </footer>
      {error && <p role="alert">{error}</p>}
    </div>
  )
}
