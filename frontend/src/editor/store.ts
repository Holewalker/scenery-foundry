import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from 'three'
import { create } from 'zustand'

export type Vec3 = [number, number, number]
export type Vec4 = [number, number, number, number]

export type AssetProcessingStatus = 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED'

export interface AssetSummary {
  id: string
  processingStatus: AssetProcessingStatus
}

export function hasPendingAssets(assets: AssetSummary[]): boolean {
  return assets.some((asset) => asset.processingStatus === 'UPLOADED' || asset.processingStatus === 'PROCESSING')
}

// Merges an incoming asset list into an existing one by id: matching entries are replaced
// with the incoming version (incoming wins per field), untouched entries are preserved, and
// unknown ids are appended in the order they arrive. Never drops entries absent from the update.
export function upsertAssetList(existing: AssetSummary[], incoming: AssetSummary[]): AssetSummary[] {
  const byId = new Map(existing.map((asset) => [asset.id, asset]))
  for (const asset of incoming) {
    byId.set(asset.id, asset)
  }
  const merged = existing.map((asset) => byId.get(asset.id) ?? asset)
  const knownIds = new Set(existing.map((asset) => asset.id))
  for (const asset of incoming) {
    if (!knownIds.has(asset.id)) {
      merged.push(asset)
      knownIds.add(asset.id)
    }
  }
  return merged
}

export interface SceneObjectDto {
  id: number
  assetId: string
  matrixContractVersion: number
  translationMm: Vec3
  quaternionXyzw: Vec4
  scale: Vec3
  matrixWorldColumnMajor: number[]
}

export interface SceneDto {
  objects: SceneObjectDto[]
}

export interface EditorObject {
  id: number
  assetId: string
  translationMm: Vec3
  quaternionXyzw: Vec4
  scale: Vec3
}

export type TransformMode = 'translate' | 'rotate'

const SNAP_TRANSLATION_MM = 50
const SNAP_ROTATION_STEP_RADIANS = MathUtils.degToRad(15)

function snapTranslation([x, y, z]: Vec3): Vec3 {
  const snap = (axis: number) => (Math.round(axis / SNAP_TRANSLATION_MM) * SNAP_TRANSLATION_MM) || 0
  return [snap(x), snap(y), snap(z)]
}

function snapQuaternion(quaternionXyzw: Vec4): Vec4 {
  const euler = new Euler().setFromQuaternion(new Quaternion(...quaternionXyzw))
  const snap = (axis: number) => Math.round(axis / SNAP_ROTATION_STEP_RADIANS) * SNAP_ROTATION_STEP_RADIANS
  const snapped = new Quaternion().setFromEuler(new Euler(snap(euler.x), snap(euler.y), snap(euler.z), euler.order))
  return [snapped.x, snapped.y, snapped.z, snapped.w]
}

function composeMatrixColumnMajor(translationMm: Vec3, quaternionXyzw: Vec4, scale: Vec3): number[] {
  const matrix = new Matrix4().compose(new Vector3(...translationMm), new Quaternion(...quaternionXyzw), new Vector3(...scale))
  return matrix.elements.slice()
}

function nextObjectId(objects: EditorObject[]): number {
  return objects.reduce((max, object) => Math.max(max, object.id), 0) + 1
}

const INITIAL_STATE = {
  assets: [] as AssetSummary[],
  objects: [] as EditorObject[],
  selectedId: null as number | null,
  mode: 'translate' as TransformMode,
  snapEnabled: false,
  orbitEnabled: true,
  dirty: false,
  loading: false,
  error: null as string | null,
}

export interface EditorState {
  assets: AssetSummary[]
  objects: EditorObject[]
  selectedId: number | null
  mode: TransformMode
  snapEnabled: boolean
  orbitEnabled: boolean
  dirty: boolean
  loading: boolean
  error: string | null
  setAssets: (assets: AssetSummary[]) => void
  upsertAssets: (assets: AssetSummary[]) => void
  insert: (assetId: string) => number
  select: (id: number | null) => void
  setMode: (mode: TransformMode) => void
  toggleSnap: () => void
  setDragging: (dragging: boolean) => void
  move: (id: number, translationMm: Vec3) => void
  rotate: (id: number, quaternionXyzw: Vec4) => void
  remove: (id: number) => void
  loadScene: (scene: SceneDto) => void
  toSceneDto: () => SceneDto
  setLoading: (loading: boolean) => void
  setError: (error: string | null) => void
}

export const useEditorStore = create<EditorState>((set, get) => ({
  ...INITIAL_STATE,
  setAssets: (assets) => set({ assets }),
  upsertAssets: (assets) => set((state) => ({ assets: upsertAssetList(state.assets, assets) })),
  insert: (assetId) => {
    const id = nextObjectId(get().objects)
    const created: EditorObject = { id, assetId, translationMm: [0, 0, 0], quaternionXyzw: [0, 0, 0, 1], scale: [1, 1, 1] }
    set((state) => ({ objects: [...state.objects, created], selectedId: id, dirty: true }))
    return id
  },
  select: (id) => set({ selectedId: id }),
  setMode: (mode) => set({ mode }),
  toggleSnap: () => set((state) => ({ snapEnabled: !state.snapEnabled })),
  setDragging: (dragging) => set({ orbitEnabled: !dragging }),
  move: (id, translationMm) =>
    set((state) => ({
      objects: state.objects.map((object) =>
        object.id === id
          ? { ...object, translationMm: state.snapEnabled ? snapTranslation(translationMm) : translationMm }
          : object,
      ),
      dirty: true,
    })),
  rotate: (id, quaternionXyzw) =>
    set((state) => ({
      objects: state.objects.map((object) =>
        object.id === id
          ? { ...object, quaternionXyzw: state.snapEnabled ? snapQuaternion(quaternionXyzw) : quaternionXyzw }
          : object,
      ),
      dirty: true,
    })),
  remove: (id) =>
    set((state) => ({
      objects: state.objects.filter((object) => object.id !== id),
      selectedId: state.selectedId === id ? null : state.selectedId,
      dirty: true,
    })),
  loadScene: (scene) =>
    set({
      objects: [...scene.objects]
        .sort((a, b) => a.id - b.id)
        .map((object) => ({
          id: object.id,
          assetId: object.assetId,
          translationMm: object.translationMm,
          quaternionXyzw: object.quaternionXyzw,
          scale: object.scale,
        })),
      selectedId: null,
      dirty: false,
    }),
  toSceneDto: () => ({
    objects: [...get().objects]
      .sort((a, b) => a.id - b.id)
      .map((object) => ({
        id: object.id,
        assetId: object.assetId,
        matrixContractVersion: 1,
        translationMm: object.translationMm,
        quaternionXyzw: object.quaternionXyzw,
        scale: object.scale,
        matrixWorldColumnMajor: composeMatrixColumnMajor(object.translationMm, object.quaternionXyzw, object.scale),
      })),
  }),
  setLoading: (loading) => set({ loading }),
  setError: (error) => set({ error }),
}))

export function resetEditorStore(): void {
  useEditorStore.setState({ ...INITIAL_STATE })
}
