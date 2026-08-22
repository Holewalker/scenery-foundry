import { useEffect, useRef, useState } from 'react'
import { captureCombinedExport, fetchCombinedExportStatus } from '../api/client'
import type { CombinedExportStatusValue } from '../api/client'
import { useEditorStore } from './store'

const POLL_INTERVAL_MS = 2000
const MAX_CONSECUTIVE_POLL_FAILURES = 5
const TERMINAL_STATUSES = new Set<CombinedExportStatusValue>(['COMPLETED', 'FAILED'])

interface ExportPanelProps {
  printGroupId: string
}

// Thin per PRD: only calls existing backend endpoints, no client-side validation. Pieces Export
// is a same-origin GET so a plain <a href> download suffices. Combined Export capture is a
// mutating POST; the download link renders only once polling reports COMPLETED (never
// RUNNING/FAILED/PENDING), mirroring the backend's own download gate.
export function ExportPanel({ printGroupId }: ExportPanelProps) {
  // Both export endpoints read PERSISTED scene_objects — an unsaved local assignment or
  // transform is invisible to them (Codex finding on PR8, #49: clicking export while dirty
  // would silently export the scene's previous, already-saved state). Gate both actions on the
  // editor being clean, matching how the toolbar's own Save button already surfaces this state.
  const dirty = useEditorStore((state) => state.dirty)
  const [exportId, setExportId] = useState<string | null>(null)
  const [status, setStatus] = useState<CombinedExportStatusValue | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [capturing, setCapturing] = useState(false)
  // Bumped only when a NEW capture starts (never per poll tick, or a slow-but-healthy response
  // would be discarded before it ever resolves — CodeRabbit/Codex findings on PR7, #48). Every
  // async callback belonging to the operation active when it was issued checks this before
  // touching state, so a stale capture or a poll from a superseded export can never win a race
  // against a newer one.
  const operationRef = useRef(0)

  useEffect(() => {
    if (!exportId || (status && TERMINAL_STATUSES.has(status))) return
    const operation = operationRef.current
    let consecutiveFailures = 0
    const interval = setInterval(() => {
      fetchCombinedExportStatus(exportId)
        .then((result) => {
          if (operation !== operationRef.current) return
          consecutiveFailures = 0
          setStatus(result.status)
          setError(result.status === 'FAILED' ? (result.errorMessage ?? 'Combined export failed') : null)
        })
        .catch(() => {
          if (operation !== operationRef.current) return
          consecutiveFailures += 1
          if (consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
            clearInterval(interval)
            setError('Lost connection while checking combined export status')
          }
        })
    }, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [exportId, status])

  async function handleCapture() {
    if (dirty) return // defensive: the button is already disabled while dirty
    const operation = ++operationRef.current // supersedes any in-flight capture/poll from before
    setError(null)
    setStatus(null)
    setExportId(null)
    setCapturing(true)
    try {
      const result = await captureCombinedExport(printGroupId)
      if (operation !== operationRef.current) return // a newer capture started before this resolved
      setExportId(result.id)
      setStatus('PENDING')
    } catch {
      if (operation !== operationRef.current) return
      setError('Failed to start combined export')
    } finally {
      if (operation === operationRef.current) setCapturing(false)
    }
  }

  const canDownload = status === 'COMPLETED' && exportId !== null

  return (
    <div className="export-panel">
      {dirty ? (
        <span aria-disabled="true">Download pieces (ZIP)</span>
      ) : (
        <a href={`/api/print-groups/${printGroupId}/pieces-export`} download>
          Download pieces (ZIP)
        </a>
      )}
      <button type="button" onClick={() => void handleCapture()} disabled={capturing || dirty}>
        Start combined export
      </button>
      {dirty && <p role="note">Save your changes to export the current scene</p>}
      {status && <p role="status">{status}</p>}
      {error && <p role="alert">{error}</p>}
      {canDownload && (
        <a href={`/api/combined-exports/${exportId}/artifact`} download>
          Download combined STL
        </a>
      )}
    </div>
  )
}
