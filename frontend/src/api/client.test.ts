import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  captureCombinedExport,
  createPrintGroup,
  deletePrintGroup,
  fetchAssetPreview,
  fetchAssets,
  fetchCombinedExportStatus,
  fetchPrintGroups,
  fetchScene,
  login,
  resetCsrfCache,
  saveScene,
  uploadAsset,
} from './client'

const originalFetch = globalThis.fetch

function jsonResponse(body: unknown, ok = true): Response {
  return { ok, status: ok ? 200 : 400, json: async () => body } as unknown as Response
}

beforeEach(() => {
  globalThis.fetch = vi.fn()
  resetCsrfCache()
})

afterEach(() => {
  globalThis.fetch = originalFetch
})

describe('api client', () => {
  it('fetches the owner-scoped asset catalog with same-origin credentials and no csrf header', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock.mockResolvedValueOnce(
      jsonResponse([
        { id: 'asset-a', processingStatus: 'READY' },
        { id: 'asset-b', processingStatus: 'UPLOADED' },
      ]),
    )

    const assets = await fetchAssets()

    expect(assets).toEqual([
      { id: 'asset-a', processingStatus: 'READY' },
      { id: 'asset-b', processingStatus: 'UPLOADED' },
    ])
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/assets')
    expect(init.credentials).toBe('same-origin')
    expect(new Headers(init.headers).has('X-CSRF-TOKEN')).toBe(false)
  })

  it('throws when the owner-scoped catalog request is rejected (e.g. unauthenticated)', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock.mockResolvedValueOnce({ ok: false, status: 401 } as Response)

    await expect(fetchAssets()).rejects.toThrow('failed to fetch assets')
  })

  it('fetches the published preview.glb bytes for an asset with same-origin credentials', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    const glbBuffer = new ArrayBuffer(4)
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, arrayBuffer: async () => glbBuffer } as unknown as Response)

    const buffer = await fetchAssetPreview('asset-a')

    expect(buffer).toBe(glbBuffer)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/assets/asset-a/preview')
    expect(init.credentials).toBe('same-origin')
  })

  it('throws when a preview is requested for a foreign or not-yet-ready asset (404)', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock.mockResolvedValueOnce({ ok: false, status: 404 } as Response)

    await expect(fetchAssetPreview('asset-foreign')).rejects.toThrow('failed to fetch asset preview')
  })

  it('uploads an STL file as multipart form data with the csrf header attached', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token-value', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ assetId: 'asset-new', processingStatus: 'UPLOADED', jobId: 'job-1' }))
    const file = new File([new Uint8Array([1, 2, 3])], 'part.stl', { type: 'application/octet-stream' })

    const result = await uploadAsset(file)

    expect(result).toEqual({ assetId: 'asset-new', processingStatus: 'UPLOADED', jobId: 'job-1' })
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url).toBe('/api/assets')
    expect(init.method).toBe('POST')
    expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('csrf-token-value')
    const body = init.body as FormData
    expect(body.get('file')).toBe(file)
  })

  it('throws when the upload is rejected by the server', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce({ ok: false, status: 413 } as Response)
    const file = new File([new Uint8Array([1])], 'huge.stl')

    await expect(uploadAsset(file)).rejects.toThrow('failed to upload asset')
  })

  it('attaches the server-provided csrf header to a mutating save request', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token-value', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ objects: [] }))

    await saveScene('project-1', { objects: [] })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/csrf')
    const [url, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(url).toBe('/api/projects/project-1/scene')
    expect(init.method).toBe('PUT')
    expect(new Headers(init.headers).get('X-CSRF-TOKEN')).toBe('csrf-token-value')
  })

  it('sends the last-known scene version and returns the server-assigned version on a successful save', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token-value', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ version: 6, objects: [] }))

    const saved = await saveScene('project-1', { version: 5, objects: [] })

    expect(saved).toEqual({ version: 6, objects: [] })
    const [, init] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(JSON.parse(init.body as string)).toEqual({ version: 5, objects: [] })
  })

  it('throws an ApiError carrying the response status and server error code on a rejected save (e.g. version conflict)', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock.mockResolvedValueOnce(jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' })).mockResolvedValueOnce({
      ok: false,
      status: 409,
      json: async () => ({ code: 'SCENE_VERSION_CONFLICT', message: 'stale version' }),
    } as unknown as Response)

    const error = await saveScene('project-1', { version: 5, objects: [] }).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(409)
    expect((error as ApiError).code).toBe('SCENE_VERSION_CONFLICT')
  })

  it('throws an ApiError with an undefined code when the error response body has none or is not JSON', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce({ ok: false, status: 500, json: async () => { throw new Error('not json') } } as unknown as Response)

    const error = await saveScene('project-1', { objects: [] }).catch((caught: unknown) => caught)

    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(500)
    expect((error as ApiError).code).toBeUndefined()
  })

  it('resolves after a successful login', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce({ ok: true, status: 204 } as Response)

    await expect(login('owner@example.com', 'secret')).resolves.toBeUndefined()
  })

  it('throws when login is rejected by the server', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock
      .mockResolvedValueOnce(jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce({ ok: false, status: 401 } as Response)

    await expect(login('owner@example.com', 'wrong')).rejects.toThrow('login failed')
  })

  it('propagates the fetch error when loading the scene fails', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock.mockResolvedValueOnce({ ok: false, status: 404 } as Response)

    await expect(fetchScene('project-missing')).rejects.toThrow('failed to fetch scene')
  })

  // apiFetch's shared csrf/same-origin plumbing is already proven generically above; these new
  // endpoint tests focus on url/method/payload shape instead of re-litigating it per endpoint.
  it('print-group + combined-export endpoints resolve on success and throw on failure', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    const csrf = jsonResponse({ token: 't', headerName: 'X-CSRF-TOKEN' })

    fetchMock.mockResolvedValueOnce(jsonResponse([{ id: 'group-1', name: 'Group 1' }]))
    expect(await fetchPrintGroups('project-1')).toEqual([{ id: 'group-1', name: 'Group 1' }])
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/projects/project-1/print-groups')
    fetchMock.mockResolvedValueOnce({ ok: false, status: 404 } as Response)
    await expect(fetchPrintGroups('project-1')).rejects.toThrow('failed to fetch print groups')

    fetchMock.mockResolvedValueOnce(csrf).mockResolvedValueOnce(jsonResponse({ id: 'group-1', name: 'Group 1' }))
    expect(await createPrintGroup('project-1', 'Group 1')).toEqual({ id: 'group-1', name: 'Group 1' })
    const [createUrl, createInit] = fetchMock.mock.calls[3] as [string, RequestInit]
    expect(createUrl).toBe('/api/projects/project-1/print-groups')
    expect(createInit.method).toBe('POST')
    expect(JSON.parse(createInit.body as string)).toEqual({ name: 'Group 1' })
    fetchMock.mockResolvedValueOnce({ ok: false, status: 422 } as Response)
    await expect(createPrintGroup('project-1', 'Group 1')).rejects.toThrow('failed to create print group')

    fetchMock.mockResolvedValueOnce({ ok: true, status: 204 } as Response)
    await expect(deletePrintGroup('group-1')).resolves.toBeUndefined()
    const [deleteUrl, deleteInit] = fetchMock.mock.calls[5] as [string, RequestInit]
    expect(deleteUrl).toBe('/api/print-groups/group-1')
    expect(deleteInit.method).toBe('DELETE')
    fetchMock.mockResolvedValueOnce({ ok: false, status: 404 } as Response)
    await expect(deletePrintGroup('group-foreign')).rejects.toThrow('failed to delete print group')

    fetchMock.mockResolvedValueOnce(jsonResponse({ exportId: 'export-1' }))
    expect(await captureCombinedExport('group-1')).toEqual({ exportId: 'export-1' })
    const [captureUrl, captureInit] = fetchMock.mock.calls[7] as [string, RequestInit]
    expect(captureUrl).toBe('/api/print-groups/group-1/combined-exports')
    expect(captureInit.method).toBe('POST')
    fetchMock.mockResolvedValueOnce({ ok: false, status: 422 } as Response)
    await expect(captureCombinedExport('group-1')).rejects.toThrow('failed to capture combined export')

    fetchMock.mockResolvedValueOnce(jsonResponse({ status: 'RUNNING' }))
    expect(await fetchCombinedExportStatus('export-1')).toEqual({ status: 'RUNNING' })
    expect(fetchMock.mock.calls[9]?.[0]).toBe('/api/combined-exports/export-1/status')
    fetchMock.mockResolvedValueOnce({ ok: false, status: 404 } as Response)
    await expect(fetchCombinedExportStatus('export-foreign')).rejects.toThrow('failed to fetch combined export status')
  })

  it('retries the csrf fetch on a later mutating request after an earlier csrf fetch failure', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock
      .mockResolvedValueOnce({ ok: false, status: 503 } as Response)
      .mockResolvedValueOnce(jsonResponse({ token: 'csrf-token-value', headerName: 'X-CSRF-TOKEN' }))
      .mockResolvedValueOnce(jsonResponse({ objects: [] }))

    await expect(saveScene('project-1', { objects: [] })).rejects.toThrow()
    await expect(saveScene('project-1', { objects: [] })).resolves.toEqual({ objects: [] })

    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/csrf')
    expect(fetchMock.mock.calls[1]?.[0]).toBe('/api/csrf')
  })
})
