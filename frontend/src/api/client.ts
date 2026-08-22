import type { AssetSummary, PrintGroupSummary, SceneDto } from '../editor/store'

/**
 * Thrown by `saveScene` (ADR-0007) so callers — chiefly `autosave.ts` — can branch on the HTTP
 * status (409 conflict vs. other 4xx vs. network/5xx) and the server's `{code, message}` body
 * without re-parsing the response themselves.
 */
export class ApiError extends Error {
  readonly status: number
  readonly code?: string

  constructor(status: number, code?: string, message?: string) {
    super(message ?? `request failed with status ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function readErrorCode(response: Response): Promise<string | undefined> {
  try {
    const body = (await response.json()) as { code?: string }
    return body.code
  } catch {
    return undefined
  }
}

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
  if (!response.ok) {
    throw new ApiError(response.status, await readErrorCode(response))
  }
  return response.json() as Promise<SceneDto>
}

export async function fetchPrintGroups(projectId: string): Promise<PrintGroupSummary[]> {
  const response = await apiFetch(`/api/projects/${projectId}/print-groups`)
  if (!response.ok) throw new Error('failed to fetch print groups')
  return response.json() as Promise<PrintGroupSummary[]>
}

export async function createPrintGroup(projectId: string, name: string): Promise<PrintGroupSummary> {
  const response = await apiFetch(`/api/projects/${projectId}/print-groups`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name }),
  })
  if (!response.ok) throw new Error('failed to create print group')
  return response.json() as Promise<PrintGroupSummary>
}

export async function deletePrintGroup(id: string): Promise<void> {
  const response = await apiFetch(`/api/print-groups/${id}`, { method: 'DELETE' })
  if (!response.ok) throw new Error('failed to delete print group')
}

export interface CombinedExportCapture {
  // Matches ExportController.capture()'s actual response body (backend/.../ExportController.java:44:
  // Map.of("id", exportId.toString())) — NOT `exportId` (Codex/CodeRabbit finding on PR7, #48: the
  // previous field name here never matched the wire shape, so every real capture silently produced
  // `undefined` and the feature never worked end-to-end).
  id: string
}

export async function captureCombinedExport(printGroupId: string): Promise<CombinedExportCapture> {
  const response = await apiFetch(`/api/print-groups/${printGroupId}/combined-exports`, { method: 'POST' })
  if (!response.ok) throw new Error('failed to capture combined export')
  return response.json() as Promise<CombinedExportCapture>
}

export type CombinedExportStatusValue = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface CombinedExportStatus {
  status: CombinedExportStatusValue
  errorCode?: string | null
  errorMessage?: string | null
}

export async function fetchCombinedExportStatus(exportId: string): Promise<CombinedExportStatus> {
  const response = await apiFetch(`/api/combined-exports/${exportId}/status`)
  if (!response.ok) throw new Error('failed to fetch combined export status')
  return response.json() as Promise<CombinedExportStatus>
}
