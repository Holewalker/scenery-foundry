import { useState } from 'react'
import type { ChangeEvent } from 'react'
import { uploadAsset } from '../api/client'
import { useEditorStore } from './store'

export function AssetUpload() {
  const [status, setStatus] = useState<string | null>(null)
  const assets = useEditorStore((state) => state.assets)
  const setAssets = useEditorStore((state) => state.setAssets)

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setStatus(null)
    try {
      const result = await uploadAsset(file)
      setAssets([...assets, { id: result.assetId, processingStatus: result.processingStatus }])
      setStatus(result.processingStatus)
    } catch {
      setStatus('Upload failed')
    }
  }

  return (
    <div className="asset-upload">
      <label>
        Upload STL
        <input type="file" accept=".stl" onChange={(event) => void handleFileChange(event)} />
      </label>
      {status && <p>{status}</p>}
    </div>
  )
}
