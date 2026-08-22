import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ExportPanel } from './ExportPanel'
import { resetEditorStore, useEditorStore } from './store'

const captureCombinedExportMock = vi.fn()
const fetchCombinedExportStatusMock = vi.fn()
vi.mock('../api/client', () => ({
  captureCombinedExport: (...args: unknown[]) => captureCombinedExportMock(...args),
  fetchCombinedExportStatus: (...args: unknown[]) => fetchCombinedExportStatusMock(...args),
}))

async function flush(advanceMs = 0): Promise<void> {
  await act(async () => {
    if (advanceMs) vi.advanceTimersByTime(advanceMs)
    await Promise.resolve()
    await Promise.resolve()
  })
}

beforeEach(() => {
  resetEditorStore()
  captureCombinedExportMock.mockReset()
  fetchCombinedExportStatusMock.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

it('renders a same-origin pieces export download link, no fetch/JS trigger needed', () => {
  render(<ExportPanel printGroupId="group-1" />)
  const link = screen.getByRole('link', { name: /download pieces/i })
  expect(link).toHaveAttribute('href', '/api/print-groups/group-1/pieces-export')
})

it('captures on click, polls status, and only enables/shows the download once COMPLETED — never for PENDING/RUNNING/FAILED', async () => {
  captureCombinedExportMock.mockResolvedValue({ id: 'export-1' })
  fetchCombinedExportStatusMock.mockResolvedValue({ status: 'PENDING' })
  vi.useFakeTimers()

  render(<ExportPanel printGroupId="group-1" />)
  fireEvent.click(screen.getByRole('button', { name: 'Start combined export' }))
  await flush()
  expect(captureCombinedExportMock).toHaveBeenCalledWith('group-1')
  expect(screen.getByRole('status')).toHaveTextContent('PENDING')
  expect(screen.queryByRole('link', { name: /download combined/i })).not.toBeInTheDocument()

  fetchCombinedExportStatusMock.mockResolvedValue({ status: 'RUNNING' })
  await flush(2000)
  expect(fetchCombinedExportStatusMock).toHaveBeenCalledWith('export-1')
  expect(screen.getByRole('status')).toHaveTextContent('RUNNING')
  expect(screen.queryByRole('link', { name: /download combined/i })).not.toBeInTheDocument()

  fetchCombinedExportStatusMock.mockResolvedValue({ status: 'COMPLETED' })
  await flush(2000)
  const downloadLink = screen.getByRole('link', { name: /download combined/i })
  expect(downloadLink).toHaveAttribute('href', '/api/combined-exports/export-1/artifact')

  // Terminal status: polling must stop (no further status calls on subsequent ticks).
  const callsAtTerminal = fetchCombinedExportStatusMock.mock.calls.length
  await flush(10000)
  expect(fetchCombinedExportStatusMock).toHaveBeenCalledTimes(callsAtTerminal)
})

it('surfaces a FAILED status with its error message, stops polling, and never shows a download link', async () => {
  captureCombinedExportMock.mockResolvedValue({ id: 'export-1' })
  fetchCombinedExportStatusMock.mockResolvedValue({ status: 'FAILED', errorMessage: 'union failed' })
  vi.useFakeTimers()

  render(<ExportPanel printGroupId="group-1" />)
  fireEvent.click(screen.getByRole('button', { name: 'Start combined export' }))
  await flush()
  await flush(2000)

  expect(screen.getByRole('alert')).toHaveTextContent('union failed')
  expect(screen.queryByRole('link', { name: /download combined/i })).not.toBeInTheDocument()

  const callsAtTerminal = fetchCombinedExportStatusMock.mock.calls.length
  await flush(10000)
  expect(fetchCombinedExportStatusMock).toHaveBeenCalledTimes(callsAtTerminal)
})

it('stops polling and surfaces an error after enough consecutive poll failures (CodeRabbit finding, PR7 #48)', async () => {
  captureCombinedExportMock.mockResolvedValue({ id: 'export-1' })
  fetchCombinedExportStatusMock.mockRejectedValue(new Error('network blip'))
  vi.useFakeTimers()

  render(<ExportPanel printGroupId="group-1" />)
  fireEvent.click(screen.getByRole('button', { name: 'Start combined export' }))
  await flush()
  expect(screen.getByRole('status')).toHaveTextContent('PENDING')

  // 5 consecutive failures trips the limit; each tick is one failed attempt.
  await flush(2000)
  await flush(2000)
  await flush(2000)
  await flush(2000)
  await flush(2000)

  expect(screen.getByRole('alert')).toHaveTextContent(/lost connection/i)
  const callsAtLimit = fetchCombinedExportStatusMock.mock.calls.length
  await flush(10000)
  expect(fetchCombinedExportStatusMock).toHaveBeenCalledTimes(callsAtLimit) // polling actually stopped
})

it('a single transient poll failure does not reset progress and a later success still completes (no false alarm)', async () => {
  captureCombinedExportMock.mockResolvedValue({ id: 'export-1' })
  fetchCombinedExportStatusMock.mockRejectedValueOnce(new Error('one-off blip'))
  fetchCombinedExportStatusMock.mockResolvedValue({ status: 'COMPLETED' })
  vi.useFakeTimers()

  render(<ExportPanel printGroupId="group-1" />)
  fireEvent.click(screen.getByRole('button', { name: 'Start combined export' }))
  await flush()
  await flush(2000) // fails once, but under the limit — no error shown
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()

  await flush(2000) // succeeds
  expect(screen.getByRole('status')).toHaveTextContent('COMPLETED')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

it('disables the capture button while a capture is in flight, preventing a concurrent second capture (CodeRabbit/Codex finding, PR7 #48)', async () => {
  let resolveCapture!: (value: { id: string }) => void
  captureCombinedExportMock.mockImplementationOnce(
    () => new Promise((resolve) => { resolveCapture = resolve })
  )

  render(<ExportPanel printGroupId="group-1" />)
  const button = screen.getByRole('button', { name: 'Start combined export' })

  fireEvent.click(button)
  await flush()
  expect(button).toBeDisabled()
  expect(captureCombinedExportMock).toHaveBeenCalledTimes(1)

  fireEvent.click(button) // must be a no-op: the button is disabled while capturing
  await flush()
  expect(captureCombinedExportMock).toHaveBeenCalledTimes(1)

  resolveCapture({ id: 'export-1' })
  await flush()
  expect(button).not.toBeDisabled()
})

it('disables both export actions while the scene has unsaved changes (Codex finding, PR8 #49)', async () => {
  useEditorStore.setState({ dirty: true })

  render(<ExportPanel printGroupId="group-1" />)

  expect(screen.getByRole('button', { name: 'Start combined export' })).toBeDisabled()
  expect(screen.queryByRole('link', { name: /download pieces/i })).not.toBeInTheDocument()
  expect(screen.getByRole('note')).toHaveTextContent(/save your changes/i)

  fireEvent.click(screen.getByRole('button', { name: 'Start combined export' }))
  await flush()
  expect(captureCombinedExportMock).not.toHaveBeenCalled()
})

it('re-enables both export actions once the scene is saved (dirty clears)', async () => {
  useEditorStore.setState({ dirty: true })
  const { rerender } = render(<ExportPanel printGroupId="group-1" />)
  expect(screen.getByRole('button', { name: 'Start combined export' })).toBeDisabled()

  useEditorStore.setState({ dirty: false })
  rerender(<ExportPanel printGroupId="group-1" />)

  expect(screen.getByRole('button', { name: 'Start combined export' })).not.toBeDisabled()
  expect(screen.getByRole('link', { name: /download pieces/i })).toBeInTheDocument()
  expect(screen.queryByRole('note')).not.toBeInTheDocument()
})
