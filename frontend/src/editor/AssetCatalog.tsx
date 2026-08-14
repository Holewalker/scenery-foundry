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
            {asset.id}
          </button>
        </li>
      ))}
    </ul>
  )
}
