import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import { createApiProxy } from './src/proxy/devProxy.ts'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': createApiProxy(),
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})
