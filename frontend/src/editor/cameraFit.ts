import { Box3, MathUtils, Vector3 } from 'three'
import type { PerspectiveCamera } from 'three'

// Matches the editor's hardcoded initial camera state (EditorCanvas's <Canvas camera={{ position:
// [0, 500, 500] }}>, target at the world origin) — the "reset camera" view used both when the
// scene has no objects yet and as the fixed viewing direction for framing a real bounding box.
const DEFAULT_POSITION: [number, number, number] = [0, 500, 500]
const DEFAULT_TARGET: [number, number, number] = [0, 0, 0]
const DEFAULT_DIRECTION = new Vector3(...DEFAULT_POSITION).normalize()
const DEFAULT_PADDING = 0.2

function isFiniteVector(vector: Vector3): boolean {
  return Number.isFinite(vector.x) && Number.isFinite(vector.y) && Number.isFinite(vector.z)
}

export interface CameraFrame {
  position: Vector3
  target: Vector3
}

/**
 * Computes the camera position + orbit target that frames `box` in view.
 *
 * A null or empty box (no scene objects) returns the fixed default view instead of attempting to
 * frame nothing — this is also the "reset camera" case. Otherwise the camera is placed along the
 * same fixed default viewing direction, offset from the box's center by a distance derived from
 * its half-diagonal and the camera's vertical/horizontal field of view (whichever is narrower),
 * padded so the box does not touch the frame edges.
 */
export function frameBox(
  box: Box3 | null,
  camera: PerspectiveCamera,
  options: { padding?: number } = {},
): CameraFrame {
  if (!box || box.isEmpty() || !isFiniteVector(box.min) || !isFiniteVector(box.max)) {
    return { position: new Vector3(...DEFAULT_POSITION), target: new Vector3(...DEFAULT_TARGET) }
  }

  const padding = options.padding ?? DEFAULT_PADDING
  const target = box.getCenter(new Vector3())
  const size = box.getSize(new Vector3())
  const halfDiagonal = size.length() / 2
  if (!isFiniteVector(target) || !isFiniteVector(size) || halfDiagonal <= Number.EPSILON) {
    return { position: new Vector3(...DEFAULT_POSITION), target: new Vector3(...DEFAULT_TARGET) }
  }

  const verticalHalfFov = MathUtils.degToRad(camera.fov) / 2
  const horizontalHalfFov = Math.atan(Math.tan(verticalHalfFov) * camera.aspect)
  // The narrower of the two fields of view is the one that would clip the box first, so it's the
  // one that must be satisfied for the whole box to stay in frame.
  const limitingHalfFov = Math.min(verticalHalfFov, horizontalHalfFov)
  const distance = (halfDiagonal / Math.sin(limitingHalfFov)) * (1 + padding)
  if (!Number.isFinite(distance) || distance <= 0) {
    return { position: new Vector3(...DEFAULT_POSITION), target: new Vector3(...DEFAULT_TARGET) }
  }

  const position = target.clone().addScaledVector(DEFAULT_DIRECTION, distance)
  if (!isFiniteVector(position)) {
    return { position: new Vector3(...DEFAULT_POSITION), target: new Vector3(...DEFAULT_TARGET) }
  }
  return { position, target }
}
