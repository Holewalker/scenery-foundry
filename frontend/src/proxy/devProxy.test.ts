import { describe, expect, it, vi } from 'vitest'
import { createApiProxy } from './devProxy'

describe('vite dev server /api proxy', () => {
  it('proxies the exact /api prefix to the backend origin without rewriting the URI', () => {
    const proxy = createApiProxy({ backendOrigin: 'http://localhost:8080' })
    expect(proxy.target).toBe('http://localhost:8080')
    expect(proxy).not.toHaveProperty('rewrite')
  })

  it('validates TLS certificates by default so a spoofed HTTPS backend origin is rejected', () => {
    const proxy = createApiProxy({ backendOrigin: 'https://backend.internal' })
    expect(proxy.secure).toBe(true)
  })

  it('only disables TLS validation when explicitly opted in for a documented local case', () => {
    const proxy = createApiProxy({ backendOrigin: 'https://localhost:8443', allowInsecureTls: true })
    expect(proxy.secure).toBe(false)
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
