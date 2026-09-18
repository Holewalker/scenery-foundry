import { Vector3 } from 'three'
import { describe, expect, it } from 'vitest'
import { intersectHorizontalDragPlane } from './directDrag'

describe('intersectHorizontalDragPlane', () => {
  it('returns the ray intersection while preserving the requested Y plane', () => {
    const point = intersectHorizontalDragPlane(new Vector3(0, 10, 0), new Vector3(1, -1, 0), 3)
    expect(point?.x).toBeCloseTo(7)
    expect(point?.y).toBeCloseTo(3)
    expect(point?.z).toBeCloseTo(0)
  })

  it('returns null for a parallel or invalid ray', () => {
    expect(intersectHorizontalDragPlane(new Vector3(0, 4, 0), new Vector3(1, 0, 0), 3)).toBeNull()
    expect(intersectHorizontalDragPlane(new Vector3(Number.NaN, 3, 0), new Vector3(1, 0, 0), 3)).toBeNull()
  })
})
