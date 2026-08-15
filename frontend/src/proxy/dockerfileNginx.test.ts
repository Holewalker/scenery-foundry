import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const dockerfile = readFileSync(resolve(__dirname, '../../Dockerfile'), 'utf8')
const runtimeStage = dockerfile.slice(dockerfile.search(/^FROM\s+nginx:/m))

describe('frontend runtime image', () => {
  it('installs nginx.conf as the active nginx configuration', () => {
    expect(runtimeStage).toMatch(
      /^COPY\s+nginx\.conf\s+\/etc\/nginx\/conf\.d\/default\.conf\s*$/m,
    )
  })

  it('serves the built SPA from the nginx document root', () => {
    expect(runtimeStage).toMatch(
      /^COPY\s+--from=build\s+\/workspace\/dist\s+\/usr\/share\/nginx\/html\s*$/m,
    )
  })
})
