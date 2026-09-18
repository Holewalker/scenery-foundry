import { Box3, MathUtils, PerspectiveCamera, Vector3 } from 'three'
import { describe, expect, it } from 'vitest'
import { frameBox } from './cameraFit'

describe('frameBox', () => {
  it('returns the fixed default view for a null box (no scene objects / reset camera)', () => {
    const camera = new PerspectiveCamera(50, 1, 0.1, 10000)

    const { position, target } = frameBox(null, camera)

    expect(position.toArray()).toEqual([0, 500, 500])
    expect(target.toArray()).toEqual([0, 0, 0])
  })

  it('returns the fixed default view for an empty Box3 (no points ever added to it)', () => {
    const camera = new PerspectiveCamera(50, 1, 0.1, 10000)

    const { position, target } = frameBox(new Box3(), camera)

    expect(position.toArray()).toEqual([0, 500, 500])
    expect(target.toArray()).toEqual([0, 0, 0])
  })

  it('falls back to the default view for non-finite or degenerate bounds', () => {
    const camera = new PerspectiveCamera(50, 1, 0.1, 10000)
    const invalid = new Box3(new Vector3(Number.NaN, 0, 0), new Vector3(10, 10, 10))
    const degenerate = new Box3(new Vector3(4, 4, 4), new Vector3(4, 4, 4))

    for (const box of [invalid, degenerate]) {
      const { position, target } = frameBox(box, camera)
      expect(position.toArray()).toEqual([0, 500, 500])
      expect(target.toArray()).toEqual([0, 0, 0])
      expect(position.toArray().every(Number.isFinite)).toBe(true)
    }
  })

  it('targets the box center and positions the camera along the default direction at a distance that frames it', () => {
    const camera = new PerspectiveCamera(50, 1, 0.1, 10000)
    const box = new Box3(new Vector3(-10, -10, -10), new Vector3(10, 30, 10))
    const center = box.getCenter(new Vector3())

    const { position, target } = frameBox(box, camera)

    expect(target.toArray()).toEqual(center.toArray())

    // Position lies along the same fixed direction as the editor's default camera, from the box center.
    const offset = position.clone().sub(center)
    const direction = offset.clone().normalize()
    const expectedDirection = new Vector3(0, 500, 500).normalize()
    expect(direction.x).toBeCloseTo(expectedDirection.x, 9)
    expect(direction.y).toBeCloseTo(expectedDirection.y, 9)
    expect(direction.z).toBeCloseTo(expectedDirection.z, 9)

    // Distance follows the half-diagonal/fov relationship (20% padding), taking whichever of the
    // vertical/horizontal fields of view is narrower.
    const size = box.getSize(new Vector3())
    const halfDiagonal = size.length() / 2
    const verticalHalfFov = MathUtils.degToRad(camera.fov) / 2
    const horizontalHalfFov = Math.atan(Math.tan(verticalHalfFov) * camera.aspect)
    const limitingHalfFov = Math.min(verticalHalfFov, horizontalHalfFov)
    const expectedDistance = (halfDiagonal / Math.sin(limitingHalfFov)) * 1.2
    expect(offset.length()).toBeCloseTo(expectedDistance, 6)
  })

  it('backs the camera further away as the box grows, and closer as padding shrinks', () => {
    const camera = new PerspectiveCamera(50, 1, 0.1, 10000)
    const smallBox = new Box3(new Vector3(-5, -5, -5), new Vector3(5, 5, 5))
    const largeBox = new Box3(new Vector3(-50, -50, -50), new Vector3(50, 50, 50))

    const smallCenter = smallBox.getCenter(new Vector3())
    const largeCenter = largeBox.getCenter(new Vector3())
    const smallDistance = frameBox(smallBox, camera).position.clone().sub(smallCenter).length()
    const largeDistance = frameBox(largeBox, camera).position.clone().sub(largeCenter).length()
    expect(largeDistance).toBeGreaterThan(smallDistance)

    const noPaddingDistance = frameBox(smallBox, camera, { padding: 0 })
      .position.clone()
      .sub(smallCenter)
      .length()
    const paddedDistance = frameBox(smallBox, camera, { padding: 0.5 })
      .position.clone()
      .sub(smallCenter)
      .length()
    expect(paddedDistance).toBeGreaterThan(noPaddingDistance)
  })

  it('accounts for a narrow (portrait) aspect ratio by widening the effective distance', () => {
    const wideCamera = new PerspectiveCamera(50, 2, 0.1, 10000)
    const narrowCamera = new PerspectiveCamera(50, 0.5, 0.1, 10000)
    const box = new Box3(new Vector3(-10, -10, -10), new Vector3(10, 10, 10))
    const center = box.getCenter(new Vector3())

    const wideDistance = frameBox(box, wideCamera).position.clone().sub(center).length()
    const narrowDistance = frameBox(box, narrowCamera).position.clone().sub(center).length()

    // A narrower viewport has a tighter horizontal FOV, so it must back off further to still fit
    // the same box width — the wide viewport does not need to.
    expect(narrowDistance).toBeGreaterThan(wideDistance)
  })
})
