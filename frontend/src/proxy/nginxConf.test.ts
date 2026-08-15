import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const conf = readFileSync(resolve(__dirname, '../../nginx.conf'), 'utf8')

describe('nginx production /api routing', () => {
  it('proxies the exact /api location to the backend service, forwarding cookies', () => {
    expect(conf).toMatch(/location\s+\/api\s*\{[^}]*proxy_pass\s+http:\/\/backend:8080;/s)
    expect(conf).toMatch(/proxy_set_header\s+Cookie\s+\$http_cookie;/)
    expect(conf).not.toMatch(/rewrite\s+\^\/api/)
  })

  it('falls back to the SPA shell for any non-/api route', () => {
    expect(conf).toMatch(/location\s+\/\s*\{[^}]*try_files\s+\$uri\s+\/index\.html;/s)
  })

  it('never intercepts an upstream failure with a SPA fallback, leaving 502 intact', () => {
    expect(conf).not.toMatch(/error_page\s+502/)
    expect(conf).not.toMatch(/proxy_intercept_errors\s+on/)
  })
})
