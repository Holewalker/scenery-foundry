import { Box3, Quaternion, Triangle, Vector3 } from 'three'
import type { BufferGeometry } from 'three'
import type { Vec3, Vec4 } from './store'

export interface LayFlatTransform {
  quaternion: Vec4
  translation: Vec3
}

export interface SupportCandidate {
  normal: Vec3
  label: string
}

export function supportCandidates(): SupportCandidate[] {
  return [
    { normal: [0, 1, 0], label: 'Top' },
    { normal: [0, -1, 0], label: 'Bottom' },
    { normal: [1, 0, 0], label: 'Right' },
    { normal: [-1, 0, 0], label: 'Left' },
    { normal: [0, 0, 1], label: 'Front' },
    { normal: [0, 0, -1], label: 'Back' },
  ]
}

/** Returns an area-weighted normal for the coplanar support region around a clicked triangle. */
export function supportNormalFromGeometry(
  geometry: BufferGeometry,
  faceIndex: number,
  fallback: Vector3,
): Vector3 {
  const positions = geometry.getAttribute('position')
  if (!positions || faceIndex < 0) return fallback.clone().normalize()
  const index = geometry.getIndex()
  const triangleCount = index ? index.count / 3 : positions.count / 3
  const clicked = fallback.clone().normalize()
  if (!Number.isFinite(clicked.lengthSq()) || clicked.lengthSq() <= Number.EPSILON) return new Vector3(0, 0, 1)
  const normal = new Vector3()
  const a = new Vector3()
  const b = new Vector3()
  const c = new Vector3()
  const candidate = new Vector3()
  const edge = new Vector3()
  const cross = new Vector3()
  const cosTolerance = Math.cos((15 * Math.PI) / 180)
  for (let triangle = 0; triangle < triangleCount; triangle += 1) {
    const read = (offset: number) => index ? index.getX(triangle * 3 + offset) : triangle * 3 + offset
    a.fromBufferAttribute(positions, read(0))
    b.fromBufferAttribute(positions, read(1))
    c.fromBufferAttribute(positions, read(2))
    Triangle.getNormal(a, b, c, candidate)
    const area = cross.subVectors(b, a).cross(edge.subVectors(c, a)).length() / 2
    if (area > Number.EPSILON && candidate.dot(clicked) >= cosTolerance) normal.addScaledVector(candidate, area)
  }
  return normal.lengthSq() > Number.EPSILON ? normal.normalize() : clicked
}

function finite(values: number[]): boolean {
  return values.every(Number.isFinite)
}

export function calculateLayFlatTransform(
  localNormal: Vec3,
  currentQuaternion: Vec4,
  currentTranslation: Vec3,
  scale: Vec3,
  localBounds: Box3,
): LayFlatTransform | null {
  if (!finite([...localNormal, ...currentQuaternion, ...currentTranslation, ...scale])) return null
  if (localNormal.every((value) => Math.abs(value) <= Number.EPSILON) || localBounds.isEmpty()) return null
  if (!finite([...localBounds.min.toArray(), ...localBounds.max.toArray()])) return null

  const current = new Quaternion(...currentQuaternion)
  const worldNormal = new Vector3(...localNormal).normalize().applyQuaternion(current).normalize()
  if (!finite(worldNormal.toArray())) return null
  // The selected outward support normal faces into the Y-up ground plane.
  const down = new Vector3(0, -1, 0)
  const alignment = worldNormal.dot(down)
  const correction = new Quaternion()
  // Explicit branches avoid THREE's arbitrary fallback axis for anti-parallel vectors. A
  // half-turn around world X provides a deterministic correction for an upward
  // support face. Other poses use the shortest correction relative to the current pose.
  if (alignment >= 1 - 1e-8) correction.identity()
  else if (alignment <= -1 + 1e-8) correction.setFromAxisAngle(new Vector3(1, 0, 0), Math.PI)
  else correction.setFromUnitVectors(worldNormal, down)
  const nextQuaternion = correction.multiply(current).normalize()

  const corners = [
    new Vector3(localBounds.min.x, localBounds.min.y, localBounds.min.z),
    new Vector3(localBounds.min.x, localBounds.min.y, localBounds.max.z),
    new Vector3(localBounds.min.x, localBounds.max.y, localBounds.min.z),
    new Vector3(localBounds.min.x, localBounds.max.y, localBounds.max.z),
    new Vector3(localBounds.max.x, localBounds.min.y, localBounds.min.z),
    new Vector3(localBounds.max.x, localBounds.min.y, localBounds.max.z),
    new Vector3(localBounds.max.x, localBounds.max.y, localBounds.min.z),
    new Vector3(localBounds.max.x, localBounds.max.y, localBounds.max.z),
  ]
  const minY = Math.min(...corners.map((corner) => corner.multiply(new Vector3(...scale)).applyQuaternion(nextQuaternion).y))
  if (!Number.isFinite(minY) || !finite(nextQuaternion.toArray())) return null
  // Rotate about the visible center rather than making an offset STL orbit its asset origin.
  const scaledCenter = localBounds.getCenter(new Vector3()).multiply(new Vector3(...scale))
  const oldCenter = scaledCenter.clone().applyQuaternion(current).add(new Vector3(...currentTranslation))
  const nextCenterOffset = scaledCenter.applyQuaternion(nextQuaternion)
  return {
    quaternion: [nextQuaternion.x, nextQuaternion.y, nextQuaternion.z, nextQuaternion.w],
    translation: [oldCenter.x - nextCenterOffset.x, -minY, oldCenter.z - nextCenterOffset.z],
  }
}
