import { useEffect, useRef, useState } from 'react'
import { captureCombinedExport, fetchCombinedExportStatus } from '../api/client'
import type { CombinedExportStatusValue } from '../api/client'

const POLL_INTERVAL_MS = 2000
const TERMINAL_STATUSES = new Set<CombinedExportStatusValue>(['COMPLETED', 'FAILED'])

interface ExportPanelProps {
  printGroupId: string
}

// Thin per PRD: only calls existing backend endpoints, no client-side validation. Pieces Export
// is a same-origin GET so a plain <a href> download suffices. Combined Export capture is a
// mutating POST; the download link renders only once polling reports COMPLETED (never
// RUNNING/FAILED/PENDING), mirroring the backend's own download gate.
export function ExportPanel({ printGroupId }: ExportPanelProps) {
  const [exportId, setExportId] = useState<string | null>(null)
  const [status, setStatus] = useState<CombinedExportStatusValue | null>(null)
  const [error, setError] = useState<string | null>(null)
  const generationRef = useRef(0)

  useEffect(() => {
    if (!exportId || (status && TERMINAL_STATUSES.has(status))) return
    const interval = setInterval(() => {
      const requestGeneration = ++generationRef.current
      fetchCombinedExportStatus(exportId)
        .then((result) => {
          if (requestGeneration !== generationRef.current) return
          setStatus(result.status)
          setError(result.status === 'FAILED' ? (result.errorMessage ?? 'Combined export failed') : null)
        })
        .catch(() => {})
    }, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [exportId, status])

  async function handleCapture() {
    setError(null)
    setStatus(null)
    try {
      const result = await captureCombinedExport(printGroupId)
      setExportId(result.exportId)
      setStatus('PENDING')
    } catch {
      setError('Failed to start combined export')
    }
  }

  const canDownload = status === 'COMPLETED' && exportId !== null

  return (
    <div className="export-panel">
      <a href={`/api/print-groups/${printGroupId}/pieces-export`} download>
        Download pieces (ZIP)
      </a>
      <button type="button" onClick={() => void handleCapture()}>
        Start combined export
      </button>
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
