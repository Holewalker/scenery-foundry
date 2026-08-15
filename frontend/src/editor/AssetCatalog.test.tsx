import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { resetEditorStore, useEditorStore } from './store'
import { AssetCatalog } from './AssetCatalog'

beforeEach(() => resetEditorStore())

describe('AssetCatalog', () => {
  it('renders the catalog assets in the given order', () => {
    render(<AssetCatalog assets={[{ id: 'asset-a' }, { id: 'asset-b' }]} />)

    const buttons = screen.getAllByRole('button')
    expect(buttons.map((button) => button.textContent)).toEqual(['asset-a', 'asset-b'])
  })

  it('inserts and selects a new object into the store when an asset is chosen', () => {
    render(<AssetCatalog assets={[{ id: 'asset-a' }]} />)

    fireEvent.click(screen.getByRole('button', { name: 'asset-a' }))

    const state = useEditorStore.getState()
    expect(state.objects).toHaveLength(1)
    expect(state.objects[0]?.assetId).toBe('asset-a')
    expect(state.selectedId).toBe(state.objects[0]?.id)
  })
})
