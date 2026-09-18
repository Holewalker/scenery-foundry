import { Box3, BoxGeometry, Matrix4, Mesh, Quaternion, Vector3 } from 'three'
import { describe, expect, it } from 'vitest'
import { centeredGeometry, pivotPosition, assetTranslation } from './objectPivot'

describe('object pivot coordinate adapter', () => {
  it('centers a copy without moving world vertices or changing the source geometry', () => {
    const source = new BoxGeometry(2, 4, 6).translate(10, 20, 30)
    const original = source.getAttribute('position').clone()
    const { geometry, center } = centeredGeometry(source)
    expect(center).toEqual([10, 20, 30])
    expect(geometry.boundingBox!.getCenter(new Vector3()).toArray()).toEqual([0, 0, 0])
    const q = new Quaternion().setFromAxisAngle(new Vector3(0, 1, 0), 0.6).toArray()
    const translation: [number, number, number] = [5, 7, 9]
    const scale: [number, number, number] = [2, 3, 4]
    const pivot = pivotPosition(translation, q, scale, center)
    const canonical = new Matrix4().compose(new Vector3(...translation), new Quaternion(...q), new Vector3(...scale))
    const rendered = new Matrix4().compose(new Vector3(...pivot), new Quaternion(...q), new Vector3(...scale))
    for (let i = 0; i < original.count; i++) {
      const expected = new Vector3().fromBufferAttribute(original, i).applyMatrix4(canonical)
      const actual = new Vector3().fromBufferAttribute(geometry.getAttribute('position'), i).applyMatrix4(rendered)
      expect(actual.distanceTo(expected)).toBeLessThan(1e-10)
      expect(new Vector3().fromBufferAttribute(source.getAttribute('position'), i).toArray()).toEqual(new Vector3().fromBufferAttribute(original, i).toArray())
    }
    expect(assetTranslation(pivot, q, scale, center)).toEqual(translation)
    const originalMesh = new Mesh(source)
    originalMesh.applyMatrix4(canonical)
    const centeredMesh = new Mesh(geometry)
    centeredMesh.applyMatrix4(rendered)
    const expectedBounds = new Box3().setFromObject(originalMesh)
    const actualBounds = new Box3().setFromObject(centeredMesh)
    expect(expectedBounds.min.distanceTo(actualBounds.min)).toBeLessThan(1e-10)
    expect(expectedBounds.max.distanceTo(actualBounds.max)).toBeLessThan(1e-10)
  })

  it('keeps a dragged rotation pivot fixed when converting back to asset coordinates', () => {
    const center: [number, number, number] = [10, 20, 30]
    const q = new Quaternion().setFromAxisAngle(new Vector3(0, 0, 1), Math.PI / 2).toArray()
    const t = assetTranslation([15, 25, 35], q, [1, 1, 1], center)
    expect(pivotPosition(t, q, [1, 1, 1], center)).toEqual([15, 25, 35])
    expect(t[0]).toBeCloseTo(35)
    expect(t[1]).toBeCloseTo(15)
    expect(t[2]).toBeCloseTo(5)
  })
})
