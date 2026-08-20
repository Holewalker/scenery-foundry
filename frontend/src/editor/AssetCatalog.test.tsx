import { act, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { AssetSummary } from './store'
import { resetEditorStore, useEditorStore } from './store'
import { AssetCatalog } from './AssetCatalog'

const fetchAssetsMock = vi.fn()
vi.mock('../api/client', () => ({ fetchAssets: (...args: unknown[]) => fetchAssetsMock(...args) }))

beforeEach(() => {
  resetEditorStore()
  fetchAssetsMock.mockReset()
})

describe('AssetCatalog', () => {
  it('renders the catalog assets in the given order', () => {
    render(
      <AssetCatalog
        assets={[
          { id: 'asset-a', processingStatus: 'READY' },
          { id: 'asset-b', processingStatus: 'READY' },
        ]}
      />,
    )

    const buttons = screen.getAllByRole('button')
    expect(buttons.map((button) => button.getAttribute('aria-label'))).toEqual(['asset-a', 'asset-b'])
  })

  it('inserts and selects a new object into the store when a READY asset is chosen', () => {
    render(<AssetCatalog assets={[{ id: 'asset-a', processingStatus: 'READY' }]} />)

    fireEvent.click(screen.getByRole('button', { name: 'asset-a' }))

    const state = useEditorStore.getState()
    expect(state.objects).toHaveLength(1)
    expect(state.objects[0]?.assetId).toBe('asset-a')
    expect(state.selectedId).toBe(state.objects[0]?.id)
  })

  it('decorates each asset button with a hidden icon without changing its accessible name or button count', () => {
    render(
      <AssetCatalog
        assets={[
          { id: 'asset-a', processingStatus: 'READY' },
          { id: 'asset-b', processingStatus: 'READY' },
        ]}
      />,
    )

    const buttons = screen.getAllByRole('button')
    expect(buttons).toHaveLength(2)

    const [firstButton] = buttons
    const icon = firstButton.querySelector('svg[aria-hidden="true"]')
    expect(icon).not.toBeNull()
    expect(firstButton.querySelector('title')).toBeNull()
    expect(firstButton.querySelector('desc')).toBeNull()
    expect(firstButton).toHaveAttribute('aria-label', 'asset-a')
  })

  it('disables inserting a non-READY asset and still shows it with a status badge', () => {
    render(<AssetCatalog assets={[{ id: 'asset-a', processingStatus: 'UPLOADED' }]} />)

    const button = screen.getByRole('button', { name: 'asset-a' })
    expect(button).toBeDisabled()
    expect(button).toHaveTextContent('UPLOADED')

    fireEvent.click(button)
    expect(useEditorStore.getState().objects).toHaveLength(0)
  })

  it('shows a distinct status badge matching each asset processing state', () => {
    render(
      <AssetCatalog
        assets={[
          { id: 'asset-a', processingStatus: 'READY' },
          { id: 'asset-b', processingStatus: 'PROCESSING' },
          { id: 'asset-c', processingStatus: 'FAILED' },
        ]}
      />,
    )

    expect(screen.getByRole('button', { name: 'asset-a' })).toHaveTextContent('READY')
    expect(screen.getByRole('button', { name: 'asset-b' })).toHaveTextContent('PROCESSING')
    expect(screen.getByRole('button', { name: 'asset-c' })).toHaveTextContent('FAILED')
  })
})

describe('AssetCatalog polling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('polls the catalog every 3 seconds while any asset is UPLOADED or PROCESSING, and applies the refreshed catalog to the store', async () => {
    fetchAssetsMock.mockResolvedValue([{ id: 'asset-a', processingStatus: 'READY' }])
    render(<AssetCatalog assets={[{ id: 'asset-a', processingStatus: 'UPLOADED' }]} />)

    expect(fetchAssetsMock).not.toHaveBeenCalled()

    await act(async () => {
      vi.advanceTimersByTime(3000)
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(fetchAssetsMock).toHaveBeenCalledTimes(1)
    expect(useEditorStore.getState().assets).toEqual([{ id: 'asset-a', processingStatus: 'READY' }])

    await act(async () => {
      vi.advanceTimersByTime(3000)
      await Promise.resolve()
    })
    expect(fetchAssetsMock).toHaveBeenCalledTimes(2)
  })

  it('never polls when every asset has already settled into READY or FAILED', () => {
    render(<AssetCatalog assets={[{ id: 'asset-a', processingStatus: 'READY' }]} />)

    act(() => {
      vi.advanceTimersByTime(10000)
    })

    expect(fetchAssetsMock).not.toHaveBeenCalled()
  })

  it('stops polling once a re-render reports every asset has settled', () => {
    fetchAssetsMock.mockResolvedValue([{ id: 'asset-a', processingStatus: 'READY' }])
    const { rerender } = render(<AssetCatalog assets={[{ id: 'asset-a', processingStatus: 'UPLOADED' }]} />)

    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(fetchAssetsMock).toHaveBeenCalledTimes(1)

    rerender(<AssetCatalog assets={[{ id: 'asset-a', processingStatus: 'READY' }]} />)

    act(() => {
      vi.advanceTimersByTime(10000)
    })
    expect(fetchAssetsMock).toHaveBeenCalledTimes(1)
  })

  it('discards a stale poll response that resolves after a newer poll already applied', async () => {
    let resolveFirst!: (value: AssetSummary[]) => void
    let resolveSecond!: (value: AssetSummary[]) => void
    const firstPoll = new Promise<AssetSummary[]>((resolve) => {
      resolveFirst = resolve
    })
    const secondPoll = new Promise<AssetSummary[]>((resolve) => {
      resolveSecond = resolve
    })
    fetchAssetsMock.mockReturnValueOnce(firstPoll).mockReturnValueOnce(secondPoll)

    render(<AssetCatalog assets={[{ id: 'asset-a', processingStatus: 'UPLOADED' }]} />)

    act(() => {
      vi.advanceTimersByTime(3000)
    })
    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(fetchAssetsMock).toHaveBeenCalledTimes(2)

    // The newer (second) request resolves first and its result must be applied.
    await act(async () => {
      resolveSecond([{ id: 'asset-a', processingStatus: 'READY' }])
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(useEditorStore.getState().assets).toEqual([{ id: 'asset-a', processingStatus: 'READY' }])

    // The older (first) request resolves out-of-order, after the newer one — it must be discarded,
    // not overwrite the newer READY state with its own stale UPLOADED/PROCESSING snapshot.
    await act(async () => {
      resolveFirst([{ id: 'asset-a', processingStatus: 'PROCESSING' }])
      await Promise.resolve()
      await Promise.resolve()
    })
    expect(useEditorStore.getState().assets).toEqual([{ id: 'asset-a', processingStatus: 'READY' }])
  })
})
