import { useState } from 'react'
import type { ChangeEvent } from 'react'
import { ApiError, uploadAsset } from '../api/client'
import { useEditorStore } from './store'

function uploadErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === 'FILE_TOO_LARGE' || error.status === 413) {
      return 'This file is too large. The maximum size is 200 MiB.'
    }
    if (error.code === 'UNSUPPORTED_MEDIA_TYPE' || error.status === 415) {
      return 'Unsupported file type. Choose an STL file.'
    }
    if (error.status === 401 || error.status === 403) {
      return 'Your session has expired. Sign in again and retry.'
    }
    if (error.status >= 500) {
      return 'Upload failed on the server. Please try again.'
    }
  }
  if (error instanceof TypeError) {
    return 'Upload could not reach the server. Check your connection and try again.'
  }
  return 'Upload failed. Please try again.'
}

export function AssetUpload() {
  const [status, setStatus] = useState<string | null>(null)
  // Shown in place of the browser's own native "Choose file / no file chosen" inline text, which
  // truncates illegibly in the panel's narrow 16rem column (e.g. Spanish locale: "Ningú...onado").
  const [fileName, setFileName] = useState<string | null>(null)
  const upsertAssets = useEditorStore((state) => state.upsertAssets)

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setFileName(file.name)
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
    } catch (error: unknown) {
      setStatus(uploadErrorMessage(error))
    }
  }

  return (
    <div className="asset-upload">
      <label className="asset-upload-trigger">
        Upload STL
        <input
          type="file"
          accept=".stl"
          className="asset-upload-input"
          onChange={(event) => void handleFileChange(event)}
        />
      </label>
      <p className="asset-upload-filename" title={fileName ?? undefined}>
        {fileName ?? 'No file chosen'}
      </p>
      {status && <p>{status}</p>}
    </div>
  )
}
