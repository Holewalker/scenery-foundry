import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './store'
import { AssetUpload } from './AssetUpload'

const uploadAssetMock = vi.fn()
vi.mock('../api/client', () => ({ uploadAsset: (...args: unknown[]) => uploadAssetMock(...args) }))

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
      expect(useEditorStore.getState().assets).toEqual([{ id: 'asset-new', processingStatus: 'UPLOADED' }]),
    )
  })

  it('shows an error message and leaves the catalog unchanged when the upload is rejected', async () => {
    uploadAssetMock.mockRejectedValue(new Error('failed to upload asset'))

    render(<AssetUpload />)
    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [selectedFile()] } })

    await waitFor(() => expect(screen.getByText('Upload failed')).toBeInTheDocument())
    expect(useEditorStore.getState().assets).toHaveLength(0)
  })

  it('does not attempt an upload when the file input is cleared without a selection', () => {
    render(<AssetUpload />)

    fireEvent.change(screen.getByLabelText('Upload STL'), { target: { files: [] } })

    expect(uploadAssetMock).not.toHaveBeenCalled()
  })
})
