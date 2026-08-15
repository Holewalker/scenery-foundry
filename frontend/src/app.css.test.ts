import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const cssPath = join(__dirname, 'app.css')
const css = readFileSync(cssPath, 'utf-8')

function extractRootBlock(source: string): { root: string; rest: string } {
  const rootStart = source.indexOf(':root')
  if (rootStart === -1) return { root: '', rest: source }
  const braceStart = source.indexOf('{', rootStart)
  let depth = 0
  let end = braceStart
  for (; end < source.length; end += 1) {
    if (source[end] === '{') depth += 1
    if (source[end] === '}') {
      depth -= 1
      if (depth === 0) break
    }
  }
  const root = source.slice(braceStart + 1, end)
  const rest = source.slice(0, rootStart) + source.slice(end + 1)
  return { root, rest }
}

const { root, rest } = extractRootBlock(css)

describe('app.css design tokens', () => {
  it('defines color, spacing, radius, elevation, and font-size tokens in :root', () => {
    expect(root).toMatch(/--color-surface\s*:/)
    expect(root).toMatch(/--color-text\s*:/)
    expect(root).toMatch(/--color-accent\s*:/)
    expect(root).toMatch(/--color-border\s*:/)
    expect(root).toMatch(/--space-1\s*:/)
    expect(root).toMatch(/--space-6\s*:/)
    expect(root).toMatch(/--radius-sm\s*:/)
    expect(root).toMatch(/--elev-1\s*:/)
    expect(root).toMatch(/--font-size-md\s*:/)
  })

  it('has no hex or rgb/rgba/hsl color literal outside :root', () => {
    const hexMatches = rest.match(/#[0-9a-fA-F]{3,8}\b/g) ?? []
    const funcColorMatches = rest.match(/\b(rgb|rgba|hsl|hsla)\(/g) ?? []

    expect(hexMatches).toEqual([])
    expect(funcColorMatches).toEqual([])
  })

  it('has no raw spacing/radius/shadow literal outside :root for box-model declarations', () => {
    const tokenBoundProps = /\b(padding|margin|gap|row-gap|column-gap|border-radius|box-shadow)\s*:\s*([^;]+);/g
    const offenders: string[] = []
    let match: RegExpExecArray | null

    while ((match = tokenBoundProps.exec(rest)) !== null) {
      const value = match[2].trim()
      const isZero = /^0(\s+0)*$/.test(value)
      const usesOnlyVars = /^(var\(--[\w-]+\)|calc\([^)]*\)|\s)+$/.test(value)
      if (!isZero && !usesOnlyVars) {
        offenders.push(`${match[1]}: ${value}`)
      }
    }

    expect(offenders).toEqual([])
  })
})
