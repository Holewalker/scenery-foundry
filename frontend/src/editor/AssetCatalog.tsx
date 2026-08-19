import { useEffect } from 'react'
import { fetchAssets } from '../api/client'
import type { AssetSummary } from './store'
import { hasPendingAssets, useEditorStore } from './store'

const POLL_INTERVAL_MS = 3000

interface AssetCatalogProps {
  assets: AssetSummary[]
}

export function AssetCatalog({ assets }: AssetCatalogProps) {
  const insert = useEditorStore((state) => state.insert)
  const setAssets = useEditorStore((state) => state.setAssets)

  // Any asset still UPLOADED/PROCESSING has no final geometry yet, so keep refreshing the
  // owner-scoped catalog until every asset has settled into READY or FAILED.
  useEffect(() => {
    if (!hasPendingAssets(assets)) return
    const interval = setInterval(() => {
      fetchAssets()
        .then(setAssets)
        .catch(() => {})
    }, POLL_INTERVAL_MS)
    return () => clearInterval(interval)
  }, [assets, setAssets])

  return (
    <ul className="asset-catalog">
      {assets.map((asset) => {
        const ready = asset.processingStatus === 'READY'
        return (
          <li key={asset.id}>
            <button type="button" aria-label={asset.id} disabled={!ready} onClick={() => ready && insert(asset.id)}>
              <span aria-hidden="true" className="asset-icon">
                <svg aria-hidden="true" focusable="false" width="16" height="16" viewBox="0 0 16 16">
                  <rect x="2" y="2" width="12" height="12" rx="2" />
                </svg>
              </span>
              <span className="asset-name">{asset.id}</span>
              <span className="asset-status" data-status={asset.processingStatus}>
                {asset.processingStatus}
              </span>
            </button>
          </li>
        )
      })}
    </ul>
  )
}
