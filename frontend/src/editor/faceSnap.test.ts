import { BoxGeometry, Quaternion, Vector3 } from 'three'
import { describe, expect, it } from 'vitest'
import { extractSurfaces, solveFaceSnap } from './faceSnap'
import type { SnapObject } from './faceSnap'

function box(id: number, position: [number, number, number], yaw = 0): SnapObject {
  const geometry = new BoxGeometry(50.8, 20, 50.8)
  return { id, translation: position, quaternion: new Quaternion().setFromAxisAngle(new Vector3(0, 1, 0), yaw).toArray(), scale: [1, 1, 1], surfaces: extractSurfaces(geometry.getAttribute('position').array, geometry.getIndex()!.array) }
}

describe('planar surface extraction', () => {
  it('joins connected coplanar triangles into six real box faces', () => {
    const data = box(1, [0, 10, 0]).surfaces
    expect(data.faces).toHaveLength(6)
    expect(data.faces.every(face => face.edges.length === 4)).toBe(true)
  })
  it('does not merge disconnected triangles just because they share a plane', () => {
    const positions = new Float32Array([0,0,0, 1,0,0, 0,1,0, 10,0,0, 11,0,0, 10,1,0])
    expect(extractSurfaces(positions).faces).toHaveLength(2)
  })
  it('reduces dense coplanar boundaries to real straight edges', () => {
    const geometry = new BoxGeometry(50.8, 20, 50.8, 20, 20, 20)
    const data = extractSurfaces(geometry.getAttribute('position').array, geometry.getIndex()!.array)
    expect(data.faces).toHaveLength(6)
    expect(data.faces.every(face => face.edges.length === 4)).toBe(true)
  })
  it('refuses meshes beyond the explicit extraction budget', () => {
    expect(extractSurfaces(new Float32Array(90), undefined, 2).limited).toBe(true)
  })
})

describe('face snapping', () => {
  it('joins real faces of 50.8 mm pieces rather than rounding centers to 50 mm', () => {
    const result = solveFaceSnap(box(2, [51.5, 10, 0]), [box(1, [0, 10, 0])])!
    expect(result.targetId).toBe(1)
    expect(result.translation[0]).toBeCloseTo(50.8)
    expect(result.translation[1]).toBeCloseTo(10)
    expect(result.translation[2]).toBeCloseTo(0)
  })
  it('autoaligns an arbitrary 22 degree target orientation without Euler quantization', () => {
    const angle = 22 * Math.PI / 180
    const target = box(1, [0, 10, 0], angle)
    const center = new Vector3(51.5, 0, 0).applyQuaternion(new Quaternion(...target.quaternion)).add(new Vector3(0,10,0))
    const result = solveFaceSnap(box(2, center.toArray(), angle + 0.05), [target])!
    expect(result.targetId).toBe(1)
    expect(new Quaternion(...result.quaternion).angleTo(new Quaternion(...target.quaternion))).toBeLessThan(1e-6)
  })
  it('anchors the nearest compatible boundary rather than recentering unequal faces', () => {
    const moving = box(2, [51.5, 10, 15])
    const target = box(1, [0, 10, 0])
    // Collinear edges overlap, so preserve the tangential position instead of matching endpoints.
    const result = solveFaceSnap(moving, [target])!
    expect(result.targetId).toBe(1)
    expect(result.translation[0]).toBeCloseTo(50.8)
    expect(result.translation[2]).toBeCloseTo(15)
  })
  it('retains canonical asset coordinates when the local origin is not its center', () => {
    const moving = box(2, [51.5, 10, 0])
    const offset = new Vector3(100, 0, 30)
    moving.surfaces.min = new Vector3(...moving.surfaces.min).add(offset).toArray()
    moving.surfaces.max = new Vector3(...moving.surfaces.max).add(offset).toArray()
    moving.surfaces.faces.forEach(face => {
      face.center = new Vector3(...face.center).add(offset).toArray()
      face.edges = face.edges.map(edge => edge.map(p => new Vector3(...p).add(offset).toArray()) as typeof edge)
    })
    moving.translation = new Vector3(...moving.translation).sub(offset).toArray()
    const result = solveFaceSnap(moving, [box(1,[0,10,0])])!
    expect(result.translation[0]).toBeCloseTo(-49.2)
    expect(result.translation[2]).toBeCloseTo(-30)
  })
  it('keeps a distant free drag unchanged rather than snapping its center to a grid', () => {
    expect(solveFaceSnap(box(2, [180, 80, 0]), [box(1, [0, 10, 0])])).toBeNull()
  })
  it('grounds a near-floor object by its bottom face, not by its center', () => {
    const result = solveFaceSnap(box(2, [13, 11, 7]), [])!
    expect(result.targetId).toBe('ground')
    expect(result.translation).toEqual([13, 10, 7])
  })
  it('ignores itself and uses stable target ordering for equivalent candidates', () => {
    const moving = box(2, [51.5, 10, 0])
    expect(solveFaceSnap(moving, [moving, box(9,[0,10,0]), box(1,[0,10,0])])!.targetId).toBe(1)
  })
})
