import { Euler, MathUtils, Quaternion } from 'three'
import { describe, expect, it } from 'vitest'
import { eulerDegreesFromQuaternion, quaternionFromEulerDegrees } from './transform'

describe('measured transform conversion', () => {
  it('round-trips Euler degrees through the stored xyzw quaternion', () => {
    const quaternion = quaternionFromEulerDegrees([10, 25, -40])
    const degrees = eulerDegreesFromQuaternion(quaternion)
    expect(degrees[0]).toBeCloseTo(10, 8)
    expect(degrees[1]).toBeCloseTo(25, 8)
    expect(degrees[2]).toBeCloseTo(-40, 8)
  })

  it('uses XYZ order and converts degrees to radians', () => {
    const quaternion = quaternionFromEulerDegrees([0, 90, 0])
    const expected = new Quaternion().setFromEuler(new Euler(0, MathUtils.degToRad(90), 0))
    expect(quaternion[0]).toBeCloseTo(expected.x, 8)
    expect(quaternion[1]).toBeCloseTo(expected.y, 8)
    expect(quaternion[2]).toBeCloseTo(expected.z, 8)
    expect(quaternion[3]).toBeCloseTo(expected.w, 8)
  })
})
