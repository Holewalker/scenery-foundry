import type { AssetSummary } from './store'
import { useEditorStore } from './store'

interface AssetCatalogProps {
  assets: AssetSummary[]
}

export function AssetCatalog({ assets }: AssetCatalogProps) {
  const insert = useEditorStore((state) => state.insert)

  return (
    <ul className="asset-catalog">
      {assets.map((asset) => (
        <li key={asset.id}>
          <button type="button" onClick={() => insert(asset.id)}>
            <span aria-hidden="true" className="asset-icon">
              <svg aria-hidden="true" focusable="false" width="16" height="16" viewBox="0 0 16 16">
                <rect x="2" y="2" width="12" height="12" rx="2" />
              </svg>
            </span>
            <span className="asset-name">{asset.id}</span>
          </button>
        </li>
      ))}
    </ul>
  )
}
