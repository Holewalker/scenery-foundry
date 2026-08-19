import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchAssetPreview, fetchAssets, fetchScene, login, resetCsrfCache, saveScene, uploadAsset } from './client'

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
