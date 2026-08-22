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
  /**
   * Fetches the current scene so the scheduler can reconcile version drift after an ambiguous
   * network/5xx save failure before blindly resending (ADR-0007, "Reintento tras respuesta
   * ambigua"). Only `.version` is used; there is no lighter version-only endpoint yet, so callers
   * currently pass the same `fetchScene` used for the initial load. A dedicated lightweight
   * "current version" endpoint would avoid pulling the full scene payload just to reconcile.
   */
  fetchScene: (projectId: string) => Promise<SceneDto>
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
  /**
   * Clears an `invalid` (non-409 4xx) suspension without discarding local edits — unlike
   * `reload()`, there is no server scene to adopt, so a fresh flush is attempted directly against
   * whatever the user has since corrected (Codex fix, PR #54). No-op unless currently suspended.
   */
  retry: () => void
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
    fetchScene,
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
  // Bumped on stop() so in-flight requests started by this instance can recognize, once their
  // awaited promise settles, that this scheduler is done with them and must not mutate the store
  // (e.g. the component unmounted/remounted or another project's scheduler now owns the store).
  let generation = 0

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
    const sentVersion = store.getSceneVersion()
    const dto: SceneDto = { ...store.toSceneDto(), version: sentVersion }
    const requestGeneration = generation

    try {
      const saved = await saveScene(projectId, dto)
      inFlight = false
      if (requestGeneration !== generation) return // stopped mid-flight: discard the stale result

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
      if (requestGeneration !== generation) return // stopped mid-flight: discard the stale result
      await handleError(error, sentVersion, revisionAtSend)
    }
  }

  async function handleError(error: unknown, sentVersion: number | null, revisionAtSend: number): Promise<void> {
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

    // Network error or 5xx: it's ambiguous whether the PUT actually applied before the response
    // was lost, so a bounded retry with exponential backoff reconciles with the server first
    // (ADR-0007, "Reintento tras respuesta ambigua") instead of blindly resending.
    scheduleReconciledRetry(sentVersion, revisionAtSend)
  }

  function scheduleReconciledRetry(sentVersion: number | null, revisionAtSend: number): void {
    attempt += 1
    if (attempt > maxRetries || !isOnline()) {
      store.setSaveState('offline')
      return
    }
    store.setSaveState('retrying')
    const delay = backoffDelayMs(attempt, baseBackoffMs, maxBackoffMs, jitter)
    backoffTimer = setTimeout(() => {
      void reconcileBeforeRetry(sentVersion, revisionAtSend)
    }, delay)
  }

  /**
   * Runs only on the network/5xx retry path (never on a direct 409, which the server already
   * compared against the live version in the same request and is always a real conflict).
   * Reconciles the ambiguous outcome of the failed PUT before resending, per ADR-0007.
   */
  async function reconcileBeforeRetry(sentVersion: number | null, revisionAtSend: number): Promise<void> {
    if (stopped || suspended) return
    const requestGeneration = generation

    let currentScene: SceneDto
    try {
      currentScene = await fetchScene(projectId)
    } catch {
      if (requestGeneration !== generation) return // stopped mid-flight: discard the stale result
      // The reconciliation GET itself failed the same way — keep the normal backoff/retry loop
      // going rather than guessing at the outcome.
      scheduleReconciledRetry(sentVersion, revisionAtSend)
      return
    }
    if (requestGeneration !== generation) return // stopped mid-flight: discard the stale result

    const serverVersion = currentScene.version ?? null

    if (serverVersion === sentVersion) {
      // The original PUT never applied: resending unchanged is safe.
      void flush()
      return
    }

    const revisionUnchangedSinceSend = store.getRevision() === revisionAtSend
    const oneVersionAhead =
      sentVersion !== null && serverVersion !== null && serverVersion === sentVersion + 1

    if (oneVersionAhead && revisionUnchangedSinceSend) {
      // The original PUT actually applied; the response was just lost in transit.
      attempt = 0
      store.markSaved(serverVersion, revisionAtSend)
      store.setSaveState('saved')
      return
    }

    if (oneVersionAhead) {
      // New edits happened while the response was in flight — not an external conflict: adopt
      // the server's version as the new baseline and retry once with it.
      store.markSaved(serverVersion, revisionAtSend)
      void flush()
      return
    }

    // Any other server version is a genuine external conflict.
    suspended = true
    clearAllTimers()
    store.setSaveState('conflict')
  }

  function reload(): void {
    suspended = false
    attempt = 0
    clearAllTimers()
    store.setSaveState('saved')
  }

  function retry(): void {
    if (!suspended) return
    suspended = false
    attempt = 0
    clearAllTimers()
    // Dismiss the stale invalid-response message the instant the user asks to retry, regardless
    // of whether the retried save succeeds — a fresh failure (handled below via flush()) sets its
    // own message; leaving the old one up would otherwise survive a successful retry, since a
    // clean save never clears `error` (that field is shared with unrelated geometry/print-group
    // failures elsewhere in the store).
    store.setSaveError(null)
    if (store.isDirty()) {
      void flush()
    } else {
      store.setSaveState('saved')
    }
  }

  function resume(): void {
    if (suspended) return // conflict/invalid need an explicit reload(), not resume()
    attempt = 0
    if (store.isDirty()) void flush()
  }

  function stop(): void {
    stopped = true
    generation += 1
    clearAllTimers()
  }

  return { notifyEdit, flushNow: flush, reload, retry, resume, stop }
}
