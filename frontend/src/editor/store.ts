import { Box3, Matrix4, Quaternion, Vector3 } from 'three'
import { create } from 'zustand'
import { calculateLayFlatTransform } from './layFlat'
import { assetTranslation } from './objectPivot'
import { solveFaceSnap } from './faceSnap'
import type { SurfaceData, SnapObject } from './faceSnap'

export type Vec3 = [number, number, number]
export type Vec4 = [number, number, number, number]

export type AssetProcessingStatus = 'UPLOADED' | 'PROCESSING' | 'READY' | 'FAILED'

export interface AssetSummary {
  id: string
  processingStatus: AssetProcessingStatus
  previewAvailable: boolean
  originalFilename: string | null
}

export interface ProjectSummary {
  id: string
  name: string | null
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
  printGroupId: string | null
  levelId: string | null
}

export interface SceneDto {
  // Undefined/omitted on a transitional pre-upgrade save request (bypasses the version check,
  // ADR-0007); null when the local scene has never been persisted yet.
  version?: number | null
  objects: SceneObjectDto[]
}

/** Mirrors the client save-state machine from ADR-0007/design.md. */
export type SaveState = 'saved' | 'unsaved' | 'saving' | 'retrying' | 'offline' | 'conflict' | 'invalid'

export interface EditorObject {
  id: number
  assetId: string
  translationMm: Vec3
  quaternionXyzw: Vec4
  scale: Vec3
  printGroupId: string | null
  levelId: string | null
}

export interface SelectedFace {
  normal: Vec3
  boundsMin: Vec3
  boundsMax: Vec3
}
export interface DragPreview {
  translationMm: Vec3
  quaternionXyzw: Vec4
}

export interface PrintGroupSummary {
  id: string
  name: string
}

export interface LevelSummary {
  id: string
  name: string
}

export type TransformMode = 'translate' | 'rotate'

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
  printGroups: [] as PrintGroupSummary[],
  levels: [] as LevelSummary[],
  selectedId: null as number | null,
  selectedFace: null as SelectedFace | null,
  dragPreview: null as DragPreview | null,
  layFlatMode: false,
  mode: 'translate' as TransformMode,
  snapEnabled: false,
  snapFeedback: null as string | null,
  orbitEnabled: true,
  dirty: false,
  loading: false,
  error: null as string | null,
  sceneVersion: null as number | null,
  revision: 0,
  saveState: 'saved' as SaveState,
  objectGeometryErrors: {} as Record<number, string>,
  geometryRetryTick: {} as Record<number, number>,
  fitRequestTick: 0,
}

export interface EditorState {
  assets: AssetSummary[]
  objects: EditorObject[]
  printGroups: PrintGroupSummary[]
  levels: LevelSummary[]
  selectedId: number | null
  selectedFace: SelectedFace | null
  dragPreview: DragPreview | null
  layFlatMode: boolean
  mode: TransformMode
  snapFeedback: string | null
  snapEnabled: boolean
  orbitEnabled: boolean
  dirty: boolean
  loading: boolean
  error: string | null
  sceneVersion: number | null
  revision: number
  saveState: SaveState
  objectGeometryErrors: Record<number, string>
  geometryRetryTick: Record<number, number>
  fitRequestTick: number
  setAssets: (assets: AssetSummary[]) => void
  upsertAssets: (assets: AssetSummary[]) => void
  insert: (assetId: string) => number
  select: (id: number | null) => void
  selectFace: (id: number, face: SelectedFace) => void
  layFlatSelected: () => void
  toggleLayFlatMode: () => void
  layFlatObject: (id: number, face: SelectedFace) => void
  setMode: (mode: TransformMode) => void
  toggleSnap: () => void
  setDragging: (dragging: boolean) => void
  setDragPreview: (preview: DragPreview | null) => void
  commitPivotTransform: (id: number, pivot: Vec3, quaternion: Vec4, center: Vec3, mode: TransformMode, snapContext?: { surfaces: SurfaceData; targets: SnapObject[] }) => void
  move: (id: number, translationMm: Vec3) => void
  rotate: (id: number, quaternionXyzw: Vec4) => void
  /** Precise measured edit; unlike drag transforms, this intentionally bypasses snapping. */
  setTranslation: (id: number, translationMm: Vec3) => void
  /** Precise measured edit; unlike drag transforms, this intentionally bypasses snapping. */
  setRotation: (id: number, quaternionXyzw: Vec4) => void
  remove: (id: number) => void
  loadScene: (scene: SceneDto) => void
  toSceneDto: () => SceneDto
  setLoading: (loading: boolean) => void
  setError: (error: string | null) => void
  setPrintGroups: (printGroups: PrintGroupSummary[]) => void
  setLevels: (levels: LevelSummary[]) => void
  assignPrintGroup: (id: number, printGroupId: string | null) => void
  assignLevel: (id: number, levelId: string | null) => void
  removePrintGroup: (groupId: string) => void
  /** Updates sceneVersion; clears dirty only when the revision has not advanced since the send. */
  markSaved: (version: number | null, revisionAtSend: number) => void
  setSaveState: (saveState: SaveState) => void
  /** Scoped per-object geometry error (EditorCanvas); `null` removes the entry. Never touches `error`. */
  setObjectGeometryError: (objectId: number, message: string | null) => void
  /** Bumps that object's retry tick so `useObjectGeometry`'s effect (keyed on it) re-runs the fetch. */
  retryObjectGeometry: (objectId: number) => void
  /** Bumps the fit request tick so EditorCanvas's fit-to-scene effect (keyed on it) re-runs. */
  requestFitToScene: () => void
}

export const useEditorStore = create<EditorState>((set, get) => ({
  ...INITIAL_STATE,
  setAssets: (assets) => set({ assets }),
  upsertAssets: (assets) => set((state) => ({ assets: upsertAssetList(state.assets, assets) })),
  insert: (assetId) => {
    const id = nextObjectId(get().objects)
    const created: EditorObject = {
      id,
      assetId,
      translationMm: [0, 0, 0],
      quaternionXyzw: [0, 0, 0, 1],
      scale: [1, 1, 1],
      printGroupId: null,
      levelId: null,
    }
    set((state) => ({ objects: [...state.objects, created], selectedId: id, dirty: true, revision: state.revision + 1 }))
    return id
  },
  select: (id) => set({ selectedId: id, selectedFace: null }),
  selectFace: (id, face) => set({ selectedId: id, selectedFace: face }),
  toggleLayFlatMode: () => set((state) => ({ layFlatMode: !state.layFlatMode })),
  layFlatObject: (id, face) => {
    const object = get().objects.find((candidate) => candidate.id === id)
    if (!object) return
    const transform = calculateLayFlatTransform(face.normal, object.quaternionXyzw, object.translationMm, object.scale,
      new Box3(new Vector3(...face.boundsMin), new Vector3(...face.boundsMax)))
    if (!transform) return
    set((state) => ({ objects: state.objects.map((candidate) => candidate.id === id ? { ...candidate, quaternionXyzw: transform.quaternion, translationMm: transform.translation } : candidate), selectedId: id, selectedFace: null, layFlatMode: false, dirty: true, revision: state.revision + 1 }))
  },
  layFlatSelected: () => {
    const state = get()
    if (state.selectedId === null || !state.selectedFace) return
    const object = state.objects.find((candidate) => candidate.id === state.selectedId)
    if (!object) return
    const transform = calculateLayFlatTransform(
      state.selectedFace.normal,
      object.quaternionXyzw,
      object.translationMm,
      object.scale,
      new Box3(new Vector3(...state.selectedFace.boundsMin), new Vector3(...state.selectedFace.boundsMax)),
    )
    if (!transform) return
    set((current) => ({
      objects: current.objects.map((candidate) =>
        candidate.id === state.selectedId
          ? { ...candidate, quaternionXyzw: transform.quaternion, translationMm: transform.translation }
          : candidate,
      ),
      dirty: true,
      revision: current.revision + 1,
    }))
  },
  setMode: (mode) => set({ mode }),
  toggleSnap: () => set((state) => ({ snapEnabled: !state.snapEnabled, snapFeedback: null })),
  setDragging: (dragging) => set({ orbitEnabled: !dragging }),
  setDragPreview: (preview) => set({ dragPreview: preview }),
  commitPivotTransform: (id, pivot, quaternion, center, mode, snapContext) => set((state) => {
    const object = state.objects.find(candidate => candidate.id === id)
    if (!object) return {}
    const nextQuaternion = mode === 'rotate' ? quaternion : object.quaternionXyzw
    const translation = assetTranslation(pivot, nextQuaternion, object.scale, center)
    const snapped = state.snapEnabled && snapContext ? solveFaceSnap({ id, translation, quaternion: nextQuaternion,
      scale: object.scale, surfaces: snapContext.surfaces }, snapContext.targets) : null
    return {
      objects: state.objects.map(candidate => candidate.id === id ? { ...candidate,
        translationMm: snapped?.translation ?? translation, quaternionXyzw: snapped?.quaternion ?? nextQuaternion } : candidate),
      snapFeedback: !state.snapEnabled ? null : snapped ? (snapped.targetId === 'ground' ? 'Snapped to ground' : 'Faces and nearest edges aligned')
        : !snapContext ? 'Face snap geometry is still preparing or unavailable' : snapContext.surfaces.limited ? 'Face snap unavailable: mesh exceeds geometry budget' : 'No nearby compatible face',
      dirty: true,
      revision: state.revision + 1,
    }
  }),
  move: (id, translationMm) =>
    set((state) => ({
      objects: state.objects.map((object) =>
        object.id === id
          ? { ...object, translationMm: translationMm }
          : object,
      ),
      dirty: true,
      revision: state.revision + 1,
    })),
  rotate: (id, quaternionXyzw) =>
    set((state) => ({
      objects: state.objects.map((object) =>
        object.id === id
          ? { ...object, quaternionXyzw: quaternionXyzw }
          : object,
      ),
      dirty: true,
      revision: state.revision + 1,
    })),
  setTranslation: (id, translationMm) => {
    if (!translationMm.every(Number.isFinite)) return
    set((state) => ({
      objects: state.objects.map((object) => (object.id === id ? { ...object, translationMm: [...translationMm] as Vec3 } : object)),
      dirty: true,
      revision: state.revision + 1,
    }))
  },
  setRotation: (id, quaternionXyzw) => {
    if (!quaternionXyzw.every(Number.isFinite)) return
    set((state) => ({
      objects: state.objects.map((object) => (object.id === id ? { ...object, quaternionXyzw: [...quaternionXyzw] as Vec4 } : object)),
      dirty: true,
      revision: state.revision + 1,
    }))
  },
  remove: (id) =>
    set((state) => ({
      objects: state.objects.filter((object) => object.id !== id),
      selectedId: state.selectedId === id ? null : state.selectedId,
      dirty: true,
      revision: state.revision + 1,
    })),
  loadScene: (scene) =>
    set({
      sceneVersion: scene.version ?? null,
      revision: 0,
      saveState: 'saved',
      objects: [...scene.objects]
        .sort((a, b) => a.id - b.id)
        .map((object) => ({
          id: object.id,
          assetId: object.assetId,
          translationMm: object.translationMm,
          quaternionXyzw: object.quaternionXyzw,
          scale: object.scale,
          // Scenes saved before this change carry neither field at all (undefined, not null),
          // even though SceneObjectDto types them as `string | null` (CodeRabbit finding, PR7
          // #48) — normalize here so toSceneDto() always round-trips an explicit null instead of
          // JSON.stringify silently omitting the key on the next save.
          printGroupId: object.printGroupId ?? null,
          levelId: object.levelId ?? null,
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
        printGroupId: object.printGroupId,
        levelId: object.levelId,
      })),
  }),
  setLoading: (loading) => set({ loading }),
  setError: (error) => set({ error }),
  setPrintGroups: (printGroups) => set({ printGroups }),
  setLevels: (levels) => set({ levels }),
  assignPrintGroup: (id, printGroupId) =>
    set((state) => ({
      objects: state.objects.map((object) => (object.id === id ? { ...object, printGroupId } : object)),
      dirty: true,
      revision: state.revision + 1,
    })),
  assignLevel: (id, levelId) =>
    set((state) => ({
      objects: state.objects.map((object) => (object.id === id ? { ...object, levelId } : object)),
      dirty: true,
      revision: state.revision + 1,
    })),
  // Atomically removes the group AND clears every object's now-dangling reference to it
  // (CodeRabbit/Codex finding on PR7, #48): the backend clears scene_objects.print_group_id on
  // delete, but local state held a stale UUID that the next scene save would send right back,
  // violating scene_objects_print_group_project_fkey. One set() call, not two separate ones, so
  // no intermediate render ever shows the group gone but the assignment still pointing at it.
  removePrintGroup: (groupId) =>
    set((state) => ({
      printGroups: state.printGroups.filter((group) => group.id !== groupId),
      objects: state.objects.map((object) =>
        object.printGroupId === groupId ? { ...object, printGroupId: null } : object
      ),
      dirty: true,
      revision: state.revision + 1,
    })),
  // Called by autosave.ts after a successful PUT; only clears dirty if no edit happened mid-flight
  // (ADR-0007 revision guard) — an edit that arrives while the request is in flight must survive.
  markSaved: (version, revisionAtSend) =>
    set((state) => ({
      sceneVersion: version ?? state.sceneVersion,
      dirty: state.revision !== revisionAtSend ? state.dirty : false,
    })),
  setSaveState: (saveState) => set({ saveState }),
  setObjectGeometryError: (objectId, message) =>
    set((state) => {
      if (message === null) {
        if (!(objectId in state.objectGeometryErrors)) return {}
        const { [objectId]: _removed, ...rest } = state.objectGeometryErrors
        return { objectGeometryErrors: rest }
      }
      return { objectGeometryErrors: { ...state.objectGeometryErrors, [objectId]: message } }
    }),
  retryObjectGeometry: (objectId) =>
    set((state) => ({
      geometryRetryTick: { ...state.geometryRetryTick, [objectId]: (state.geometryRetryTick[objectId] ?? 0) + 1 },
    })),
  requestFitToScene: () => set((state) => ({ fitRequestTick: state.fitRequestTick + 1 })),
}))

export function resetEditorStore(): void {
  useEditorStore.setState({ ...INITIAL_STATE })
}
