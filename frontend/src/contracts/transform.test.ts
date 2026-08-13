import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { Matrix4, Vector3 } from 'three'
import { describe, expect, it } from 'vitest'

type TransformFixture = {
  contract: string
  version: number
  cases: Array<{
    name: string
    matrixColumnMajor: number[]
    point: [number, number, number]
    expectedPoint: [number, number, number]
  }>
}

const fixturePath = resolve(process.cwd(), '../contracts/fixtures/transform-v1.json')

describe('transform contract v1', () => {
  it('applies the persisted Matrix4.elements order to column vectors', () => {
    const fixture = JSON.parse(readFileSync(fixturePath, 'utf8')) as TransformFixture
    expect(fixture.contract).toBe('scenery-foundry.transform')
    expect(fixture.version).toBe(1)

    for (const testCase of fixture.cases) {
      const matrix = new Matrix4().fromArray(testCase.matrixColumnMajor)
      expect(matrix.elements).toEqual(testCase.matrixColumnMajor)

      const actual = new Vector3(...testCase.point).applyMatrix4(matrix).toArray()
      actual.forEach((value, index) =>
        expect(value, testCase.name).toBeCloseTo(testCase.expectedPoint[index], 12),
      )
    }
  })
})
