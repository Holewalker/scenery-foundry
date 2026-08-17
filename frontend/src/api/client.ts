import type { AssetSummary, SceneDto } from '../editor/store'

interface CsrfToken {
  token: string
  headerName: string
}

let cachedCsrfToken: Promise<CsrfToken> | null = null

export function resetCsrfCache(): void {
  cachedCsrfToken = null
}

async function fetchCsrfToken(): Promise<CsrfToken> {
  cachedCsrfToken ??= fetch('/api/csrf', { credentials: 'same-origin' })
    .then((response) => {
      if (!response.ok) throw new Error('failed to fetch csrf token')
      return response.json() as Promise<CsrfToken>
    })
    .catch((error: unknown) => {
      cachedCsrfToken = null
      throw error
    })
  return cachedCsrfToken
}

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (MUTATING_METHODS.has(method)) {
    const csrf = await fetchCsrfToken()
    headers.set(csrf.headerName, csrf.token)
  }
  return fetch(path, { ...init, credentials: 'same-origin', headers })
}

export async function login(email: string, password: string): Promise<void> {
  const response = await apiFetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  })
  if (!response.ok) throw new Error('login failed')
}

export async function fetchAssets(): Promise<AssetSummary[]> {
  const response = await apiFetch('/api/assets')
  if (!response.ok) throw new Error('failed to fetch assets')
  return response.json() as Promise<AssetSummary[]>
}

export async function fetchAssetPreview(assetId: string): Promise<ArrayBuffer> {
  const response = await apiFetch(`/api/assets/${assetId}/preview`)
  if (!response.ok) throw new Error('failed to fetch asset preview')
  return response.arrayBuffer()
}

export interface AssetIntakeResult {
  assetId: string
  processingStatus: AssetSummary['processingStatus']
  jobId: string
}

export async function uploadAsset(file: File): Promise<AssetIntakeResult> {
  const body = new FormData()
  body.append('file', file)
  const response = await apiFetch('/api/assets', { method: 'POST', body })
  if (!response.ok) throw new Error('failed to upload asset')
  return response.json() as Promise<AssetIntakeResult>
}

export async function fetchScene(projectId: string): Promise<SceneDto> {
  const response = await apiFetch(`/api/projects/${projectId}/scene`)
  if (!response.ok) throw new Error('failed to fetch scene')
  return response.json() as Promise<SceneDto>
}

export async function saveScene(projectId: string, scene: SceneDto): Promise<SceneDto> {
  const response = await apiFetch(`/api/projects/${projectId}/scene`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(scene),
  })
  if (!response.ok) throw new Error('failed to save scene')
  return response.json() as Promise<SceneDto>
}
