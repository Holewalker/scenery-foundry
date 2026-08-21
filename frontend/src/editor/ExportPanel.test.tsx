import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ExportPanel } from './ExportPanel'

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
  captureCombinedExportMock.mockResolvedValue({ exportId: 'export-1' })
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
  captureCombinedExportMock.mockResolvedValue({ exportId: 'export-1' })
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
