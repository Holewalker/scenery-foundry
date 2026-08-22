import { useEffect, useRef } from 'react'
import type { SaveState } from './store'

const ACTIVE_EXCEPTION_STATES: ReadonlySet<SaveState> = new Set(['saving', 'retrying', 'offline', 'conflict', 'invalid'])

const LABELS: Record<SaveState, string> = {
  saved: 'Saved',
  unsaved: 'Unsaved changes',
  saving: 'Saving…',
  retrying: 'Retrying…',
  offline: 'Offline — changes kept locally',
  conflict: 'Conflict — reload required',
  invalid: 'Save failed',
}

// Pure: 'saving'/'retrying'/'offline'/'conflict'/'invalid' are exception states that always win
// over the plain dirty flag; otherwise the label is simply derived from dirty (saved/unsaved).
// Kept separate from `dirty`/`saveState` bookkeeping in the store itself (see store.ts markSaved)
// so ordinary edits during a conflict/invalid suspension never silently clobber that state.
export function resolveDisplaySaveState(dirty: boolean, saveState: SaveState): SaveState {
  if (ACTIVE_EXCEPTION_STATES.has(saveState)) return saveState
  return dirty ? 'unsaved' : 'saved'
}

export interface SaveStatusProps {
  dirty: boolean
  saveState: SaveState
  errorMessage?: string | null
  onReload: () => void
}

export function SaveStatus({ dirty, saveState, errorMessage, onReload }: SaveStatusProps) {
  const displayState = resolveDisplaySaveState(dirty, saveState)
  const reloadButtonRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (displayState === 'conflict') {
      reloadButtonRef.current?.focus()
    }
  }, [displayState])

  return (
    <>
      <p className="save-state" role="status">
        {LABELS[displayState]}
      </p>
      {displayState === 'conflict' && (
        <div role="alertdialog" aria-modal="true" aria-labelledby="conflict-title" aria-describedby="conflict-description">
          <h2 id="conflict-title">Someone else saved this scene</h2>
          <p id="conflict-description">
            This project was updated elsewhere since you last loaded it. Reload the latest scene to keep working;
            your local changes since then will be discarded.
          </p>
          <button type="button" ref={reloadButtonRef} onClick={onReload}>
            Reload latest scene
          </button>
        </div>
      )}
      {displayState === 'invalid' && errorMessage && <p role="alert">{errorMessage}</p>}
    </>
  )
}
