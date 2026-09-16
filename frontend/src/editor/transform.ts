import { Euler, MathUtils, Quaternion } from 'three'
import type { Vec4 } from './store'

export type EulerDegrees = [number, number, number]

export function quaternionFromEulerDegrees([x, y, z]: EulerDegrees): Vec4 {
  const quaternion = new Quaternion().setFromEuler(
    new Euler(MathUtils.degToRad(x), MathUtils.degToRad(y), MathUtils.degToRad(z), 'XYZ'),
  )
  return [quaternion.x, quaternion.y, quaternion.z, quaternion.w]
}

export function eulerDegreesFromQuaternion(quaternionXyzw: Vec4): EulerDegrees {
  const euler = new Euler().setFromQuaternion(new Quaternion(...quaternionXyzw), 'XYZ')
  return [MathUtils.radToDeg(euler.x), MathUtils.radToDeg(euler.y), MathUtils.radToDeg(euler.z)]
}
