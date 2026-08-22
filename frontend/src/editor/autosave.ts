import type { SaveState, SceneDto } from './store'

/** The narrow slice of the editor store the scheduler needs, kept framework-free per D5. */
export interface AutosaveStoreApi {
  getRevision: () => number
  getSceneVersion: () => number | null
  isDirty: () => boolean
  toSceneDto: () => SceneDto
  /** Updates sceneVersion; clears dirty only when the revision has not advanced since the send. */
  markSaved: (version: number | null, revisionAtSend: number) => void
  setSaveState: (state: SaveState) => void
  setSaveError: (message: string | null) => void
}

export interface AutosaveDeps {
  projectId: string
  store: AutosaveStoreApi
  saveScene: (projectId: string, scene: SceneDto) => Promise<SceneDto>
  idleMs?: number
  ceilingMs?: number
  maxRetries?: number
  baseBackoffMs?: number
  maxBackoffMs?: number
  /** Returns a value in [0, 1); injectable for deterministic backoff tests. Default: Math.random. */
  jitter?: () => number
  isOnline?: () => boolean
}

export interface AutosaveScheduler {
  /** Call after every scene-mutating store action. */
  notifyEdit: () => void
  /** Bypasses the idle debounce and attempts an immediate save (used by the manual Save button). */
  flushNow: () => Promise<void>
  /** Clears the conflict/invalid suspension after the caller reloads the scene from the server. */
  reload: () => void
  /** Resumes after an offline suspension (e.g. on the browser's `online` event). */
  resume: () => void
  /** Tears down all pending timers; call on unmount. */
  stop: () => void
}

interface ApiErrorLike {
  status?: number
}

function backoffDelayMs(attempt: number, baseBackoffMs: number, maxBackoffMs: number, jitter: () => number): number {
  const capped = Math.min(maxBackoffMs, baseBackoffMs * 2 ** (attempt - 1))
  return capped * jitter()
}

export function createAutosaveScheduler(deps: AutosaveDeps): AutosaveScheduler {
  const {
    projectId,
    store,
    saveScene,
    idleMs = 2000,
    ceilingMs = 15000,
    maxRetries = 5,
    baseBackoffMs = 1000,
    maxBackoffMs = 16000,
    jitter = Math.random,
    isOnline = () => typeof navigator === 'undefined' || navigator.onLine !== false,
  } = deps

  let idleTimer: ReturnType<typeof setTimeout> | null = null
  let ceilingTimer: ReturnType<typeof setTimeout> | null = null
  let backoffTimer: ReturnType<typeof setTimeout> | null = null
  let inFlight = false
  let pendingWhileInFlight = false
  let suspended = false // conflict / invalid — no auto-retry until reload()/user action
  let attempt = 0
  let stopped = false

  function clearIdle(): void {
    if (idleTimer) {
      clearTimeout(idleTimer)
      idleTimer = null
    }
  }

  function clearCeiling(): void {
    if (ceilingTimer) {
      clearTimeout(ceilingTimer)
      ceilingTimer = null
    }
  }

  function clearBackoff(): void {
    if (backoffTimer) {
      clearTimeout(backoffTimer)
      backoffTimer = null
    }
  }

  function clearAllTimers(): void {
    clearIdle()
    clearCeiling()
    clearBackoff()
  }

  function armCeilingIfNeeded(): void {
    if (ceilingTimer || !store.isDirty()) return
    ceilingTimer = setTimeout(() => {
      void flush()
    }, ceilingMs)
  }

  function notifyEdit(): void {
    if (stopped || suspended) return
    if (inFlight) {
      // Edits during an in-flight save coalesce into a single follow-up after it resolves.
      pendingWhileInFlight = true
      return
    }
    clearIdle()
    idleTimer = setTimeout(() => {
      void flush()
    }, idleMs)
    armCeilingIfNeeded()
  }

  async function flush(): Promise<void> {
    if (stopped || suspended) return
    clearIdle()
    clearCeiling()
    clearBackoff()
    if (!store.isDirty()) return
    if (inFlight) {
      pendingWhileInFlight = true
      return
    }

    inFlight = true
    store.setSaveState('saving')
    const revisionAtSend = store.getRevision()
    const dto: SceneDto = { ...store.toSceneDto(), version: store.getSceneVersion() }

    try {
      const saved = await saveScene(projectId, dto)
      inFlight = false
      attempt = 0
      store.markSaved(saved.version ?? null, revisionAtSend)
      store.setSaveState('saved')

      const editsHappenedDuringFlight = pendingWhileInFlight || store.getRevision() !== revisionAtSend
      pendingWhileInFlight = false
      if (editsHappenedDuringFlight) {
        void flush()
      }
    } catch (error) {
      inFlight = false
      pendingWhileInFlight = false
      await handleError(error)
    }
  }

  async function handleError(error: unknown): Promise<void> {
    const status = (error as ApiErrorLike)?.status

    if (status === 409) {
      suspended = true
      clearAllTimers()
      store.setSaveState('conflict')
      return
    }

    if (status === 401 || status === 403) {
      suspended = true
      clearAllTimers()
      store.setSaveState('invalid')
      store.setSaveError('Your session has expired. Please sign in again.')
      return
    }

    if (status !== undefined && status >= 400 && status < 500) {
      suspended = true
      clearAllTimers()
      store.setSaveState('invalid')
      store.setSaveError('The scene could not be saved: please fix the error and try again.')
      return
    }

    // Network error or 5xx: bounded retry with exponential backoff and full jitter.
    attempt += 1
    if (attempt > maxRetries || !isOnline()) {
      store.setSaveState('offline')
      return
    }
    store.setSaveState('retrying')
    const delay = backoffDelayMs(attempt, baseBackoffMs, maxBackoffMs, jitter)
    backoffTimer = setTimeout(() => {
      void flush()
    }, delay)
  }

  function reload(): void {
    suspended = false
    attempt = 0
    clearAllTimers()
    store.setSaveState('saved')
  }

  function resume(): void {
    if (suspended) return // conflict/invalid need an explicit reload(), not resume()
    attempt = 0
    if (store.isDirty()) void flush()
  }

  function stop(): void {
    stopped = true
    clearAllTimers()
  }

  return { notifyEdit, flushNow: flush, reload, resume, stop }
}
