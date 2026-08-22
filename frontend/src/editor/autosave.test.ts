import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createAutosaveScheduler } from './autosave'
import type { AutosaveStoreApi } from './autosave'
import type { SceneDto } from './store'

interface FakeState {
  revision: number
  sceneVersion: number | null
  dirty: boolean
}

function createFakeStore() {
  const state: FakeState = { revision: 0, sceneVersion: 0, dirty: false }
  const store: AutosaveStoreApi = {
    getRevision: () => state.revision,
    getSceneVersion: () => state.sceneVersion,
    isDirty: () => state.dirty,
    toSceneDto: (): SceneDto => ({ objects: [] }),
    markSaved: vi.fn((version: number | null, revisionAtSend: number) => {
      state.sceneVersion = version ?? state.sceneVersion
      if (state.revision === revisionAtSend) state.dirty = false
    }),
    setSaveState: vi.fn(),
    setSaveError: vi.fn(),
  }
  return { store, state }
}

function edit(state: FakeState) {
  state.dirty = true
  state.revision += 1
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('autosave scheduler', () => {
  it('fires a save 2s after the scene becomes idle, sending the last-known scene version', async () => {
    const { store, state } = createFakeStore()
    const saveScene = vi.fn().mockResolvedValue({ version: 1, objects: [] })
    const scheduler = createAutosaveScheduler({ projectId: 'p1', store, saveScene })

    edit(state)
    scheduler.notifyEdit()
    expect(saveScene).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1999)
    expect(saveScene).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1)
    expect(saveScene).toHaveBeenCalledTimes(1)
    expect(saveScene).toHaveBeenCalledWith('p1', { objects: [], version: 0 })
  })

  it('forces a save at the 15s ceiling even when edits keep resetting the 2s idle timer', async () => {
    const { store, state } = createFakeStore()
    const saveScene = vi.fn().mockResolvedValue({ version: 1, objects: [] })
    const scheduler = createAutosaveScheduler({ projectId: 'p1', store, saveScene })

    edit(state)
    scheduler.notifyEdit()
    for (let elapsed = 0; elapsed < 14000; elapsed += 1000) {
      await vi.advanceTimersByTimeAsync(1000)
      edit(state)
      scheduler.notifyEdit() // keeps resetting the idle timer, so idle alone never fires
    }
    expect(saveScene).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(1000) // crosses the 15s ceiling since the first dirty edit
    expect(saveScene).toHaveBeenCalledTimes(1)
  })

  it('coalesces edits that occur while a save is in flight into exactly one follow-up save', async () => {
    const { store, state } = createFakeStore()
    let resolveFirst: (value: SceneDto) => void = () => {}
    const saveScene = vi
      .fn()
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce({ version: 2, objects: [] })
    const scheduler = createAutosaveScheduler({ projectId: 'p1', store, saveScene })

    edit(state)
    scheduler.notifyEdit()
    await vi.advanceTimersByTimeAsync(2000)
    expect(saveScene).toHaveBeenCalledTimes(1)

    edit(state)
    scheduler.notifyEdit()
    edit(state)
    scheduler.notifyEdit()
    edit(state)
    scheduler.notifyEdit()

    resolveFirst({ version: 1, objects: [] })
    await vi.advanceTimersByTimeAsync(0)
    await Promise.resolve()
    await Promise.resolve()

    expect(saveScene).toHaveBeenCalledTimes(2)
  })

  it('keeps dirty and reschedules a follow-up save when the revision advanced during the in-flight save (revision guard)', async () => {
    const { store, state } = createFakeStore()
    let resolveFirst: (value: SceneDto) => void = () => {}
    let resolveSecond: (value: SceneDto) => void = () => {}
    const saveScene = vi
      .fn()
      .mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveSecond = resolve }))
    const scheduler = createAutosaveScheduler({ projectId: 'p1', store, saveScene })

    edit(state)
    scheduler.notifyEdit()
    await vi.advanceTimersByTimeAsync(2000)
    expect(saveScene).toHaveBeenCalledTimes(1)

    // An edit happens mid-flight WITHOUT calling notifyEdit — proves markSaved's own revision
    // comparison (not just the scheduler's pending flag) is what keeps dirty true.
    state.revision += 1
    state.dirty = true

    resolveFirst({ version: 2, objects: [] })
    await vi.advanceTimersByTimeAsync(0)
    await Promise.resolve()
    await Promise.resolve()

    expect(store.markSaved).toHaveBeenCalledWith(2, 1)
    expect(state.dirty).toBe(true) // markSaved refused to clear dirty: revision moved during flight
    expect(state.sceneVersion).toBe(2)
    expect(saveScene).toHaveBeenCalledTimes(2) // scheduler independently detected the mismatch and re-flushed

    resolveSecond({ version: 3, objects: [] })
    await vi.advanceTimersByTimeAsync(0)
    await Promise.resolve()
    await Promise.resolve()

    expect(state.dirty).toBe(false) // now settled: the follow-up save matched the final revision
    expect(state.sceneVersion).toBe(3)
  })

  it('retries a network failure with exponential backoff and gives up after the retry budget is exhausted', async () => {
    const { store, state } = createFakeStore()
    const saveScene = vi.fn().mockRejectedValue(new Error('network error'))
    const scheduler = createAutosaveScheduler({
      projectId: 'p1',
      store,
      saveScene,
      maxRetries: 2,
      baseBackoffMs: 1000,
      maxBackoffMs: 16000,
      jitter: () => 1,
    })

    edit(state)
    scheduler.notifyEdit()
    await vi.advanceTimersByTimeAsync(2000)
    expect(saveScene).toHaveBeenCalledTimes(1)
    expect(store.setSaveState).toHaveBeenLastCalledWith('retrying')

    await vi.advanceTimersByTimeAsync(1000) // 1st backoff: base*2^0 = 1000ms
    expect(saveScene).toHaveBeenCalledTimes(2)
    expect(store.setSaveState).toHaveBeenLastCalledWith('retrying')

    await vi.advanceTimersByTimeAsync(2000) // 2nd backoff: base*2^1 = 2000ms
    expect(saveScene).toHaveBeenCalledTimes(3)
    expect(store.setSaveState).toHaveBeenLastCalledWith('offline') // retry budget (2) exhausted
  })

  it('transitions to conflict on a 409 response, suspends further autosave, and reload() resumes it', async () => {
    const { store, state } = createFakeStore()
    const conflictError = Object.assign(new Error('conflict'), { status: 409 })
    const saveScene = vi
      .fn()
      .mockRejectedValueOnce(conflictError)
      .mockResolvedValueOnce({ version: 5, objects: [] })
    const scheduler = createAutosaveScheduler({ projectId: 'p1', store, saveScene })

    edit(state)
    scheduler.notifyEdit()
    await vi.advanceTimersByTimeAsync(2000)
    expect(store.setSaveState).toHaveBeenLastCalledWith('conflict')

    // Further edits are ignored while suspended — no additional save attempted, even past the ceiling.
    edit(state)
    scheduler.notifyEdit()
    await vi.advanceTimersByTimeAsync(20000)
    expect(saveScene).toHaveBeenCalledTimes(1)

    scheduler.reload()
    edit(state)
    scheduler.notifyEdit()
    await vi.advanceTimersByTimeAsync(2000)
    expect(saveScene).toHaveBeenCalledTimes(2)
  })

  it('transitions to invalid on a non-409 4xx response and stops autosave without retrying', async () => {
    const { store, state } = createFakeStore()
    const validationError = Object.assign(new Error('bad request'), { status: 422 })
    const saveScene = vi.fn().mockRejectedValue(validationError)
    const scheduler = createAutosaveScheduler({ projectId: 'p1', store, saveScene })

    edit(state)
    scheduler.notifyEdit()
    await vi.advanceTimersByTimeAsync(2000)

    expect(saveScene).toHaveBeenCalledTimes(1)
    expect(store.setSaveState).toHaveBeenLastCalledWith('invalid')
    expect(store.setSaveError).toHaveBeenCalled()

    edit(state)
    scheduler.notifyEdit()
    await vi.advanceTimersByTimeAsync(20000)
    expect(saveScene).toHaveBeenCalledTimes(1) // suspended: no further attempts
  })
})
