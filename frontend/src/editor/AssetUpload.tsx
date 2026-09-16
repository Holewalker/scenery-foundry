import { useState } from 'react'
import type { ChangeEvent } from 'react'
import { uploadAsset } from '../api/client'
import { useEditorStore } from './store'

export function AssetUpload() {
  const [status, setStatus] = useState<string | null>(null)
  const upsertAssets = useEditorStore((state) => state.upsertAssets)

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setStatus(null)
    try {
      const result = await uploadAsset(file)
      // Merge by id instead of replacing from a closed-over snapshot: the poll effect may have
      // applied catalog updates while this upload was in flight, and those must not be dropped.
      // previewAvailable is always false immediately after upload (no preview yet); originalFilename
      // is known client-side from the File object, so it's shown right away instead of waiting for
      // the next poll to backfill it.
      upsertAssets([
        {
          id: result.assetId,
          processingStatus: result.processingStatus,
          previewAvailable: false,
          originalFilename: file.name,
        },
      ])
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
