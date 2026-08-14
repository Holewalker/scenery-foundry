import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchAssets, fetchScene, login, resetCsrfCache, saveScene } from './client'

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
  it('fetches the ordered asset catalog with same-origin credentials and no csrf header', async () => {
    const fetchMock = globalThis.fetch as ReturnType<typeof vi.fn>
    fetchMock.mockResolvedValueOnce(jsonResponse([{ id: 'asset-a' }, { id: 'asset-b' }]))

    const assets = await fetchAssets('project-1')

    expect(assets).toEqual([{ id: 'asset-a' }, { id: 'asset-b' }])
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('/api/projects/project-1/assets')
    expect(init.credentials).toBe('same-origin')
    expect(new Headers(init.headers).has('X-CSRF-TOKEN')).toBe(false)
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
})
