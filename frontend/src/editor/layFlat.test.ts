import { Box3, Euler, Quaternion, Vector3 } from 'three'
import { describe, expect, it } from 'vitest'
import { calculateLayFlatTransform, supportCandidates } from './layFlat'

const bounds = new Box3(new Vector3(-1, -2, -3), new Vector3(1, 2, 4))

describe('calculateLayFlatTransform', () => {
  it('keeps an offset asset center in place horizontally while changing its support face', () => {
    const offsetBounds = new Box3(new Vector3(10, 20, 30), new Vector3(12, 24, 36))
    const result = calculateLayFlatTransform([1, 0, 0], [0, 0, 0, 1], [5, 7, 9], [1, 1, 1], offsetBounds)!
    const center = offsetBounds.getCenter(new Vector3()).applyQuaternion(new Quaternion(...result.quaternion)).add(new Vector3(...result.translation))
    expect(center.x).toBeCloseTo(16)
    expect(center.z).toBeCloseTo(42)
    expect(center.y).toBeCloseTo(1)
  })
  it.each(supportCandidates())('places the $label support normal downward on the Y-up ground', ({ normal }) => {
    const current = new Quaternion().setFromEuler(new Euler(0.3, -0.7, 0.4))
    const result = calculateLayFlatTransform(normal, current.toArray(), [10, 20, 30], [1, 1, 1], bounds)!
    const aligned = new Vector3(...normal).applyQuaternion(new Quaternion(...result.quaternion))
    expect(aligned.x).toBeCloseTo(0)
    expect(aligned.y).toBeCloseTo(-1)
    expect(aligned.z).toBeCloseTo(0)
    const oldCenter = bounds.getCenter(new Vector3()).applyQuaternion(current).add(new Vector3(10, 20, 30))
    const newCenter = bounds.getCenter(new Vector3()).applyQuaternion(new Quaternion(...result.quaternion)).add(new Vector3(...result.translation))
    expect(newCenter.x).toBeCloseTo(oldCenter.x)
    expect(newCenter.z).toBeCloseTo(oldCenter.z)
    const heights = []
    for (const x of [-1, 1]) for (const y of [-2, 2]) for (const z of [-3, 4]) {
      heights.push(new Vector3(x, y, z).applyQuaternion(new Quaternion(...result.quaternion)).y + result.translation[1])
    }
    expect(Math.min(...heights)).toBeCloseTo(0)
  })

  it('preserves orientation and heading when the bottom face already points down', () => {
    const current = new Quaternion().setFromAxisAngle(new Vector3(0, 1, 0), 0.8)
    const result = calculateLayFlatTransform([0, -1, 0], current.toArray(), [10, 20, 30], [1, 2, 1], bounds)!
    expect(result.quaternion).toEqual(current.toArray())
    expect(result.translation).toEqual([10, 4, 30])
  })

  it('turns the top face down by a deterministic half-turn', () => {
    const result = calculateLayFlatTransform([0, 1, 0], [0, 0, 0, 1], [0, 0, 0], [1, 1, 1], bounds)!
    expect(result.quaternion[0]).toBeCloseTo(1)
    expect(result.quaternion[1]).toBeCloseTo(0)
    expect(result.quaternion[2]).toBeCloseTo(0)
    expect(result.quaternion[3]).toBeCloseTo(0)
  })

  it('rejects degenerate normals and non-finite bounds', () => {
    expect(calculateLayFlatTransform([0, 0, 0], [0, 0, 0, 1], [0, 0, 0], [1, 1, 1], new Box3())).toBeNull()
  })

  it('labels candidates consistently with Y-up and XZ ground', () => {
    expect(supportCandidates()).toEqual([
      { normal: [0, 1, 0], label: 'Top' },
      { normal: [0, -1, 0], label: 'Bottom' },
      { normal: [1, 0, 0], label: 'Right' },
      { normal: [-1, 0, 0], label: 'Left' },
      { normal: [0, 0, 1], label: 'Front' },
      { normal: [0, 0, -1], label: 'Back' },
    ])
  })
})
