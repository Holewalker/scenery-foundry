import { Quaternion, Vector3 } from 'three'
import type { BufferGeometry } from 'three'
import type { Vec3, Vec4 } from './store'

/** Render-only centering; persisted asset coordinates and source bytes stay unchanged. */
export function centeredGeometry(source: BufferGeometry): { geometry: BufferGeometry; center: Vec3 } {
  const geometry = source.clone()
  geometry.computeBoundingBox()
  const center = geometry.boundingBox!.getCenter(new Vector3())
  geometry.translate(-center.x, -center.y, -center.z)
  return { geometry, center: center.toArray() as Vec3 }
}

function offset(center: Vec3, quaternion: Vec4, scale: Vec3): Vector3 {
  return new Vector3(...center).multiply(new Vector3(...scale)).applyQuaternion(new Quaternion(...quaternion))
}

export function pivotPosition(translation: Vec3, quaternion: Vec4, scale: Vec3, center: Vec3): Vec3 {
  return offset(center, quaternion, scale).add(new Vector3(...translation)).toArray() as Vec3
}

export function assetTranslation(pivot: Vec3, quaternion: Vec4, scale: Vec3, center: Vec3): Vec3 {
  return new Vector3(...pivot).sub(offset(center, quaternion, scale)).toArray() as Vec3
}
