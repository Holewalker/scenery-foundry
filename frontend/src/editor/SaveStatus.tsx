import { useEffect, useRef } from 'react'
import type { KeyboardEvent as ReactKeyboardEvent } from 'react'
import type { SaveState } from './store'

const ACTIVE_EXCEPTION_STATES: ReadonlySet<SaveState> = new Set(['saving', 'retrying', 'offline', 'conflict', 'invalid'])

// Elements a keyboard user can legitimately land on inside the modal dialog.
const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

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
  /** Clears the invalid-response suspension and retries the save (Codex fix, PR #54). */
  onRetry: () => void
}

export function SaveStatus({ dirty, saveState, errorMessage, onReload, onRetry }: SaveStatusProps) {
  const displayState = resolveDisplaySaveState(dirty, saveState)
  const reloadButtonRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (displayState === 'conflict') {
      reloadButtonRef.current?.focus()
    }
  }, [displayState])

  // Traps Tab/Shift+Tab focus cycling within the modal alertdialog while it is open: `aria-modal`
  // alone only declares modality, it does not stop the browser from moving focus into the rest of
  // the editor behind it. The caller additionally marks the background `inert` (SaveStatus has no
  // reach into the surrounding DOM to do that itself).
  function handleDialogKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (event.key !== 'Tab' || !dialogRef.current) return
    const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
    if (focusable.length === 0) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey) {
      if (document.activeElement === first) {
        event.preventDefault()
        last.focus()
      }
    } else if (document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <>
      <p className="save-state" role="status">
        {LABELS[displayState]}
      </p>
      {displayState === 'conflict' && (
        <div
          ref={dialogRef}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="conflict-title"
          aria-describedby="conflict-description"
          onKeyDown={handleDialogKeyDown}
        >
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
      {displayState === 'invalid' && (
        <>
          {errorMessage && <p role="alert">{errorMessage}</p>}
          <button type="button" onClick={onRetry}>
            Retry save
          </button>
        </>
      )}
    </>
  )
}
