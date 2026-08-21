import { Euler, MathUtils, Quaternion } from 'three'
import { beforeEach, describe, expect, it } from 'vitest'
import { hasPendingAssets, resetEditorStore, useEditorStore } from './store'

const identityQuaternion: [number, number, number, number] = [0, 0, 0, 1]

beforeEach(() => {
  resetEditorStore()
})

describe('editor store', () => {
  it('inserts objects with deterministic positive JS-safe ids and selects the inserted object', () => {
    const firstId = useEditorStore.getState().insert('asset-1')
    const secondId = useEditorStore.getState().insert('asset-2')
    expect(firstId).toBe(1)
    expect(secondId).toBe(2)
    expect(Number.isSafeInteger(secondId)).toBe(true)
    expect(secondId).toBeGreaterThan(0)
    expect(useEditorStore.getState().selectedId).toBe(secondId)
    expect(useEditorStore.getState().objects.map((object) => object.id)).toEqual([1, 2])
  })

  it('selects and deletes objects, clearing selection for the deleted object', () => {
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)
    expect(useEditorStore.getState().selectedId).toBe(id)
    useEditorStore.getState().remove(id)
    expect(useEditorStore.getState().objects).toHaveLength(0)
    expect(useEditorStore.getState().selectedId).toBeNull()
  })

  it('moves and rotates an object without snapping when snapping is disabled', () => {
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().move(id, [12.5, 0, -3.2])
    useEditorStore.getState().rotate(id, [0, 0.7071067811865476, 0, 0.7071067811865476])
    const object = useEditorStore.getState().objects[0]
    expect(object.translationMm).toEqual([12.5, 0, -3.2])
    expect(object.quaternionXyzw[1]).toBeCloseTo(0.7071067811865476, 9)
  })

  it('snaps translation to a 50mm grid when snapping is enabled', () => {
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().toggleSnap()
    useEditorStore.getState().move(id, [62, -8, 24])
    expect(useEditorStore.getState().objects[0].translationMm).toEqual([50, 0, 0])
  })

  it('snaps rotation to 15 degree increments when snapping is enabled', () => {
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().toggleSnap()
    const rotated = new Quaternion().setFromEuler(new Euler(0, MathUtils.degToRad(20), 0))
    useEditorStore.getState().rotate(id, [rotated.x, rotated.y, rotated.z, rotated.w])
    const expected = new Quaternion().setFromEuler(new Euler(0, MathUtils.degToRad(15), 0))
    const actual = useEditorStore.getState().objects[0].quaternionXyzw
    expect(actual[0]).toBeCloseTo(expected.x, 9)
    expect(actual[1]).toBeCloseTo(expected.y, 9)
    expect(actual[2]).toBeCloseTo(expected.z, 9)
    expect(actual[3]).toBeCloseTo(expected.w, 9)
  })

  it('keeps scale locked and read-only while translate and rotate remain editable', () => {
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().move(id, [10, 20, 30])
    useEditorStore.getState().rotate(id, identityQuaternion)
    expect(useEditorStore.getState().objects[0].scale).toEqual([1, 1, 1])
    expect(useEditorStore.getState()).not.toHaveProperty('setScale')
  })

  it('disables orbit control while a transform drag is active and restores it afterward', () => {
    expect(useEditorStore.getState().orbitEnabled).toBe(true)
    useEditorStore.getState().setDragging(true)
    expect(useEditorStore.getState().orbitEnabled).toBe(false)
    useEditorStore.getState().setDragging(false)
    expect(useEditorStore.getState().orbitEnabled).toBe(true)
  })

  it('serializes objects to a deterministic scene DTO sorted by id', () => {
    useEditorStore.getState().insert('asset-b')
    useEditorStore.getState().insert('asset-a')
    const dto = useEditorStore.getState().toSceneDto()
    expect(dto.objects.map((object) => object.id)).toEqual([1, 2])
    expect(dto.objects[0]).toMatchObject({
      matrixContractVersion: 1,
      translationMm: [0, 0, 0],
      quaternionXyzw: identityQuaternion,
      scale: [1, 1, 1],
    })
    expect(dto.objects[0].matrixWorldColumnMajor).toHaveLength(16)
  })

  it('reconstructs deterministic state from a saved scene on reload', () => {
    useEditorStore.getState().loadScene({
      objects: [
        { id: 5, assetId: 'asset-a', matrixContractVersion: 1, translationMm: [1, 2, 3], quaternionXyzw: identityQuaternion, scale: [1, 1, 1], matrixWorldColumnMajor: new Array(16).fill(0), printGroupId: 'group-1', levelId: null },
        { id: 2, assetId: 'asset-b', matrixContractVersion: 1, translationMm: [0, 0, 0], quaternionXyzw: identityQuaternion, scale: [1, 1, 1], matrixWorldColumnMajor: new Array(16).fill(0), printGroupId: null, levelId: 'level-1' },
      ],
    })
    expect(useEditorStore.getState().objects.map((object) => object.id)).toEqual([2, 5])
    expect(useEditorStore.getState().dirty).toBe(false)
    expect(useEditorStore.getState().selectedId).toBeNull()
    expect(useEditorStore.getState().insert('asset-c')).toBe(6)
  })
})

describe('print group / level assignment', () => {
  it('assigns/reassigns/unassigns print group and level independently, without affecting other objects (single-group, dirty-tracked)', () => {
    const id = useEditorStore.getState().insert('asset-1')
    const otherId = useEditorStore.getState().insert('asset-2')
    const of = (objId: number) => useEditorStore.getState().objects.find((o) => o.id === objId)
    expect(of(id)?.printGroupId).toBeNull()
    expect(of(id)?.levelId).toBeNull()

    useEditorStore.getState().assignPrintGroup(id, 'group-1')
    useEditorStore.getState().assignPrintGroup(otherId, 'group-1')
    expect(of(id)?.printGroupId).toBe('group-1')
    expect(useEditorStore.getState().dirty).toBe(true)

    useEditorStore.getState().assignPrintGroup(id, 'group-2')
    expect(of(id)?.printGroupId).toBe('group-2')
    useEditorStore.getState().assignPrintGroup(id, null)
    expect(of(id)?.printGroupId).toBeNull()
    expect(of(otherId)?.printGroupId).toBe('group-1') // unaffected by id's reassignment/unassignment

    useEditorStore.getState().assignLevel(id, 'level-1')
    expect(of(id)?.levelId).toBe('level-1')
    useEditorStore.getState().assignLevel(id, 'level-2')
    expect(of(id)?.levelId).toBe('level-2')
    useEditorStore.getState().assignLevel(id, null)
    expect(of(id)?.levelId).toBeNull()
  })

  it('sets the print groups/levels catalogs, and round-trips printGroupId/levelId through loadScene and toSceneDto', () => {
    useEditorStore.getState().setPrintGroups([{ id: 'group-1', name: 'Group 1' }])
    useEditorStore.getState().setLevels([{ id: 'level-1', name: 'Level 1' }])
    expect(useEditorStore.getState().printGroups).toEqual([{ id: 'group-1', name: 'Group 1' }])
    expect(useEditorStore.getState().levels).toEqual([{ id: 'level-1', name: 'Level 1' }])

    useEditorStore.getState().loadScene({
      objects: [
        { id: 5, assetId: 'asset-a', matrixContractVersion: 1, translationMm: [1, 2, 3], quaternionXyzw: identityQuaternion, scale: [1, 1, 1], matrixWorldColumnMajor: new Array(16).fill(0), printGroupId: 'group-1', levelId: 'level-1' },
      ],
    })
    expect(useEditorStore.getState().objects[0]).toMatchObject({ printGroupId: 'group-1', levelId: 'level-1' })
    expect(useEditorStore.getState().toSceneDto().objects[0]).toMatchObject({ printGroupId: 'group-1', levelId: 'level-1' })
  })
})

describe('upsertAssets', () => {
  it('updates matching entries by id, preserves untouched entries, and appends unknown ids in order', () => {
    useEditorStore.getState().setAssets([
      { id: 'asset-a', processingStatus: 'UPLOADED' },
      { id: 'asset-b', processingStatus: 'PROCESSING' },
    ])

    useEditorStore.getState().upsertAssets([
      { id: 'asset-b', processingStatus: 'READY' },
      { id: 'asset-c', processingStatus: 'UPLOADED' },
    ])

    expect(useEditorStore.getState().assets).toEqual([
      { id: 'asset-a', processingStatus: 'UPLOADED' },
      { id: 'asset-b', processingStatus: 'READY' },
      { id: 'asset-c', processingStatus: 'UPLOADED' },
    ])
  })

  it('leaves existing state untouched when merging an empty list', () => {
    useEditorStore.getState().setAssets([{ id: 'asset-a', processingStatus: 'READY' }])
    useEditorStore.getState().upsertAssets([])
    expect(useEditorStore.getState().assets).toEqual([{ id: 'asset-a', processingStatus: 'READY' }])
  })
})

describe('hasPendingAssets', () => {
  it('reports pending when any asset is still UPLOADED or PROCESSING', () => {
    expect(hasPendingAssets([{ id: 'a', processingStatus: 'UPLOADED' }])).toBe(true)
    expect(hasPendingAssets([{ id: 'a', processingStatus: 'PROCESSING' }])).toBe(true)
  })

  it('reports no pending work once every asset has settled into READY or FAILED', () => {
    expect(hasPendingAssets([])).toBe(false)
    expect(
      hasPendingAssets([
        { id: 'a', processingStatus: 'READY' },
        { id: 'b', processingStatus: 'FAILED' },
      ]),
    ).toBe(false)
  })
})
