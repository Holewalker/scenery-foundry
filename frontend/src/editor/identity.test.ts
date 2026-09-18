import { describe, expect, it } from 'vitest'
import { labelWithFallback, shortId } from './identity'

describe('shortId', () => {
  it('returns the first 8 hex characters of a UUID', () => {
    expect(shortId('a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d')).toBe('a1b2c3d4')
  })

  it('returns the whole string when it is shorter than 8 characters', () => {
    expect(shortId('abc')).toBe('abc')
  })
})

describe('labelWithFallback', () => {
  it('returns the name when present', () => {
    expect(labelWithFallback('My Project', 'a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d', 'Project')).toBe('My Project')
  })

  it('falls back to "<prefix> <short id>" when the name is null', () => {
    expect(labelWithFallback(null, 'a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d', 'Project')).toBe('Project a1b2c3d4')
  })
})
