import { describe, expect, it, vi } from 'vitest'
import { createApiProxy } from './devProxy'

describe('vite dev server /api proxy', () => {
  it('proxies the exact /api prefix to the backend origin without rewriting the URI', () => {
    const proxy = createApiProxy({ backendOrigin: 'http://localhost:8080' })
    expect(proxy.target).toBe('http://localhost:8080')
    expect(proxy).not.toHaveProperty('rewrite')
  })

  it('never rewrites cookie headers so session and CSRF cookies pass through untouched', () => {
    const proxy = createApiProxy()
    expect(proxy).not.toHaveProperty('cookieDomainRewrite')
    expect(proxy).not.toHaveProperty('cookiePathRewrite')
    expect(proxy).not.toHaveProperty('autoRewrite')
  })

  it('responds with an explicit 502 when the backend upstream is unreachable', () => {
    const proxy = createApiProxy()
    const handlers = new Map<string, (...args: unknown[]) => void>()
    const fakeProxyServer = { on: vi.fn((event: string, handler: (...args: unknown[]) => void) => handlers.set(event, handler)) }
    proxy.configure?.(fakeProxyServer as never, {} as never)
    const response = { headersSent: false, writeHead: vi.fn(), end: vi.fn() }
    handlers.get('error')?.(new Error('ECONNREFUSED'), {}, response)
    expect(response.writeHead).toHaveBeenCalledWith(502, expect.objectContaining({ 'Content-Type': expect.any(String) }))
    expect(response.end).toHaveBeenCalled()
  })
})
