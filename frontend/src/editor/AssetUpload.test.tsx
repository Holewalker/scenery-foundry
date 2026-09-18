import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './store'
import { AssetUpload } from './AssetUpload'
import { ApiError } from '../api/client'

const uploadAssetMock = vi.fn()
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, uploadAsset: (...args: unknown[]) => uploadAssetMock(...args) }
})

function selectedFile(): File {
  return new File([new Uint8Array([1, 2, 3])], 'part.stl', { type: 'application/octet-stream' })
}

beforeEach(() => {
  resetEditorStore()
  uploadAssetMock.mockReset()
})

describe('AssetUpload', () => {
  it('uploads the selected STL file as multipart form data and shows the returned UPLOADED status', async () => {
    uploadAssetMock.mockResolvedValue({ assetId: 'asset-new', processingStatus: 'UPLOADED', jobId: 'job-1' })

    render(<AssetUpload />)

    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [selectedFile()] } })

    await waitFor(() => expect(uploadAssetMock).toHaveBeenCalledTimes(1))
    expect(uploadAssetMock.mock.calls[0]?.[0]).toBeInstanceOf(File)
    await waitFor(() => expect(screen.getByText('UPLOADED')).toBeInTheDocument())
  })

  it('adds the newly uploaded asset to the catalog store', async () => {
    uploadAssetMock.mockResolvedValue({ assetId: 'asset-new', processingStatus: 'UPLOADED', jobId: 'job-1' })

    render(<AssetUpload />)
    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [selectedFile()] } })

    await waitFor(() =>
      expect(useEditorStore.getState().assets).toEqual([
        { id: 'asset-new', processingStatus: 'UPLOADED', previewAvailable: false, originalFilename: 'part.stl' },
      ]),
    )
  })

  it('shows a specific message for a file that exceeds the server limit', async () => {
    uploadAssetMock.mockRejectedValue(new ApiError(413, 'FILE_TOO_LARGE', 'Uploaded file exceeds the maximum allowed size'))

    render(<AssetUpload />)
    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [selectedFile()] } })

    await waitFor(() => expect(screen.getByText('This file is too large. The maximum size is 200 MiB.')).toBeInTheDocument())
    expect(screen.queryByText('Upload failed')).not.toBeInTheDocument()
    expect(useEditorStore.getState().assets).toHaveLength(0)
  })

  it('explains unsupported media types and authentication failures without exposing raw errors', async () => {
    uploadAssetMock.mockRejectedValueOnce(new ApiError(415, 'UNSUPPORTED_MEDIA_TYPE', 'Uploaded content is not an STL file'))
    const { rerender } = render(<AssetUpload />)
    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [selectedFile()] } })
    await waitFor(() => expect(screen.getByText('Unsupported file type. Choose an STL file.')).toBeInTheDocument())

    uploadAssetMock.mockRejectedValueOnce(new ApiError(401, 'UNAUTHENTICATED', 'private server detail'))
    rerender(<AssetUpload />)
    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [selectedFile()] } })
    await waitFor(() => expect(screen.getByText('Your session has expired. Sign in again and retry.')).toBeInTheDocument())
    expect(screen.queryByText('private server detail')).not.toBeInTheDocument()
  })

  it('shows a connection message for network failures', async () => {
    uploadAssetMock.mockRejectedValue(new TypeError('Failed to fetch'))
    render(<AssetUpload />)
    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [selectedFile()] } })
    await waitFor(() => expect(screen.getByText('Upload could not reach the server. Check your connection and try again.')).toBeInTheDocument())
  })

  // Regression: the panel's 16rem column is too narrow for the browser's own native "Choose
  // file / no file chosen" inline text (e.g. Spanish locale: "Ningú...onado"), so the component
  // must render the full filename itself instead of relying on that native chrome for display.
  it('renders the full, untruncated selected filename, while keeping the input\'s accessible name intact', async () => {
    uploadAssetMock.mockResolvedValue({ assetId: 'asset-new', processingStatus: 'UPLOADED', jobId: 'job-1' })
    const longName = 'a-fairly-long-original-model-name.stl'
    const longFile = new File([new Uint8Array([1, 2, 3])], longName, { type: 'application/octet-stream' })

    render(<AssetUpload />)
    const input = screen.getByLabelText('Upload STL')
    fireEvent.change(input, { target: { files: [longFile] } })

    expect(screen.getByText(longName)).toBeInTheDocument()
    expect(screen.getByLabelText('Upload STL')).toBe(input)

    await waitFor(() => expect(uploadAssetMock).toHaveBeenCalledTimes(1))
  })

  it('shows "No file chosen" before any file has been selected', () => {
    render(<AssetUpload />)

    expect(screen.getByText('No file chosen')).toBeInTheDocument()
  })

  it('does not attempt an upload when the file input is cleared without a selection', () => {
    render(<AssetUpload />)

    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [] } })

    expect(uploadAssetMock).not.toHaveBeenCalled()
  })

  it('does not drop an asset the poll added to the store while the upload was still in flight', async () => {
    let resolveUpload!: (value: { assetId: string; processingStatus: 'UPLOADED'; jobId: string }) => void
    uploadAssetMock.mockReturnValue(
      new Promise((resolve) => {
        resolveUpload = resolve
      }),
    )

    render(<AssetUpload />)
    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [selectedFile()] } })
    await waitFor(() => expect(uploadAssetMock).toHaveBeenCalledTimes(1))

    // A concurrent poll (AssetCatalog's effect) applies a status update for an unrelated
    // asset while this upload's request is still in flight — this must NOT be lost.
    act(() => {
      useEditorStore.getState().upsertAssets([
        { id: 'asset-from-poll', processingStatus: 'READY', previewAvailable: true, originalFilename: null },
      ])
    })

    act(() => {
      resolveUpload({ assetId: 'asset-new', processingStatus: 'UPLOADED', jobId: 'job-1' })
    })

    await waitFor(() =>
      expect(useEditorStore.getState().assets).toEqual(
        expect.arrayContaining([
          { id: 'asset-from-poll', processingStatus: 'READY', previewAvailable: true, originalFilename: null },
          { id: 'asset-new', processingStatus: 'UPLOADED', previewAvailable: false, originalFilename: 'part.stl' },
        ]),
      ),
    )
    expect(useEditorStore.getState().assets).toHaveLength(2)
  })
})
