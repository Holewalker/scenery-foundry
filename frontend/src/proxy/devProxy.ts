import type { ProxyOptions } from 'vite'

export interface DevProxyOptions {
  backendOrigin?: string
}

const DEFAULT_BACKEND_ORIGIN = 'http://localhost:8080'

export function createApiProxy(options: DevProxyOptions = {}): ProxyOptions {
  const target = options.backendOrigin ?? process.env.VITE_BACKEND_ORIGIN ?? DEFAULT_BACKEND_ORIGIN
  return {
    target,
    changeOrigin: true,
    secure: false,
    configure(proxy) {
      proxy.on('error', (_error, _request, response) => {
        const httpResponse = response as { headersSent?: boolean; writeHead: (status: number, headers: Record<string, string>) => void; end: (body?: string) => void }
        if (!httpResponse.headersSent) {
          httpResponse.writeHead(502, { 'Content-Type': 'text/plain' })
        }
        httpResponse.end('Bad Gateway')
      })
    },
  }
}
