import { act, render, waitFor } from '@testing-library/react'
import { forwardRef } from 'react'
import type { Ref, ReactNode } from 'react'
import { Box3, BoxGeometry, PerspectiveCamera, Quaternion, Vector3 } from 'three'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './store'
import { EditorCanvas } from './EditorCanvas'

const fetchAssetPreviewMock = vi.fn()
vi.mock('../api/client', () => ({ fetchAssetPreview: (...args: unknown[]) => fetchAssetPreviewMock(...args) }))

// Fakes the GLTF scene graph shape a real GLTFLoader.parseAsync would resolve: a root scene
// Group whose descendants include exactly one Mesh (matching trimesh's plain, un-nested export).
function fakeGltfScene(geometry: unknown) {
  // Use genuine geometry so the render-only pivot adapter is exercised.
  const input = geometry as { clone?: unknown; boundingBox?: Box3 }
  const realGeometry = input.clone ? geometry : new BoxGeometry(2, 2, 2)
  if (!input.clone && input.boundingBox) {
    const size = input.boundingBox.getSize(new Vector3())
    const center = input.boundingBox.getCenter(new Vector3())
    ;(realGeometry as BoxGeometry).scale(size.x / 2, size.y / 2, size.z / 2).translate(center.x, center.y, center.z)
  }
  const mesh = { isMesh: true, geometry: realGeometry }
  return {
    scene: {
      traverse: (callback: (object: unknown) => void) => callback(mesh),
    },
  }
}

const parseAsyncSpy = vi.fn((buffer: unknown, _path: string) => Promise.resolve(fakeGltfScene({ buffer })))
vi.mock('three/examples/jsm/loaders/GLTFLoader.js', () => ({
  GLTFLoader: class {
    parseAsync(buffer: unknown, path: string) {
      return parseAsyncSpy(buffer, path)
    }
  },
}))

const orbitSpy = vi.fn()
const transformSpy = vi.fn()
// A real PerspectiveCamera/Vector3 pair stands in for R3F's own camera/OrbitControls instances —
// this harness renders <Canvas>/<group>/<mesh> as plain DOM nodes with no scene graph behind
// them (see EditorCanvas.tsx's `instanceof Object3D` guard), so the fit effect always treats the
// scene as empty here and only the default-view branch is exercised through this component.
const fakeCamera = new PerspectiveCamera(50, 1, 0.1, 10000)
const fakeControls = { target: new Vector3(), update: vi.fn() }
vi.mock('@react-three/fiber', () => ({
  Canvas: ({ children }: { children: ReactNode }) => <>{children}</>,
  useThree: () => ({ camera: fakeCamera }),
}))
vi.mock('@react-three/drei', () => ({
  Html: ({ children }: { children: ReactNode }) => <>{children}</>,
  OrbitControls: forwardRef((props: Record<string, unknown>, ref: Ref<typeof fakeControls>) => {
    orbitSpy(props)
    if (typeof ref === 'function') ref(fakeControls)
    else if (ref) ref.current = fakeControls
    return null
  }),
  TransformControls: (props: { children: ReactNode } & Record<string, unknown>) => {
    transformSpy(props)
    return <>{props.children}</>
  },
}))

beforeEach(() => {
  resetEditorStore()
  fetchAssetPreviewMock.mockReset().mockResolvedValue(new ArrayBuffer(0))
  orbitSpy.mockReset()
  transformSpy.mockReset()
  parseAsyncSpy.mockClear().mockImplementation((buffer: unknown) => Promise.resolve(fakeGltfScene({ buffer })))
  fakeCamera.position.set(0, 500, 500)
  fakeControls.target.set(0, 0, 0)
  fakeControls.update.mockReset()
})

// The mocked TransformControls renders the real <mesh> child directly into the DOM (no R3F
// reconciler in this test environment), so meshRef.current is a plain DOM node with no
// .position/.quaternion. Stub them the way a real THREE.Object3D would provide them, so
// handleObjectChange can read a final transform when onMouseUp commits it.
function stubMeshTransform(
  container: HTMLElement,
  position: [number, number, number] = [0, 0, 0],
  quaternion: [number, number, number, number] = [0, 0, 0, 1],
) {
  const meshEl = container.querySelector('mesh') as unknown as {
    position: { toArray: () => number[] }
    quaternion: { toArray: () => number[] }
  }
  meshEl.position = { toArray: () => position }
  meshEl.quaternion = { toArray: () => quaternion }
}

/** Seeds a single READY, previewAvailable asset — the precondition for a fetch to be attempted. */
function seedPreviewableAsset(id: string) {
  useEditorStore
    .getState()
    .setAssets([{ id, processingStatus: 'READY', previewAvailable: true, originalFilename: null }])
}

describe('EditorCanvas', () => {
  it('attaches controls to the centered mesh and persists pivot rotation in asset coordinates', async () => {
    const geometry = new BoxGeometry(2, 4, 6).translate(10, 20, 30)
    parseAsyncSpy.mockResolvedValueOnce(fakeGltfScene(geometry))
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().setTranslation(id, [5, 7, 9])
    useEditorStore.getState().setMode('rotate')
    const { container } = render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())
    const mesh = container.querySelector('mesh')!
    const props = transformSpy.mock.calls.at(-1)![0]
    expect(props.object.current).toBe(mesh)
    expect(mesh).toHaveAttribute('position', '15,27,39')
    expect(useEditorStore.getState().objects[0].translationMm).toEqual([5, 7, 9])
    const q = new Quaternion().setFromAxisAngle(new Vector3(0, 0, 1), Math.PI / 2).toArray()
    stubMeshTransform(container, [15, 27, 39], q)
    act(() => props.onMouseUp())
    const object = useEditorStore.getState().objects[0]
    expect(object.translationMm[0]).toBeCloseTo(35)
    expect(object.translationMm[1]).toBeCloseTo(17)
    expect(object.translationMm[2]).toBeCloseTo(9)
    expect(object.quaternionXyzw).toEqual(q)
    const dto = useEditorStore.getState().toSceneDto().objects[0]
    expect(dto.matrixWorldColumnMajor.slice(12, 15)).toEqual(object.translationMm)
  })

  it('reattaches the gizmo to the centered mesh after laying an offset asset flat', async () => {
    const geometry = new BoxGeometry(2, 4, 6).translate(10, 20, 30)
    parseAsyncSpy.mockResolvedValueOnce(fakeGltfScene(geometry))
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().setTranslation(id, [5, 7, 9])
    const { container } = render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())
    act(() => {
      useEditorStore.getState().toggleLayFlatMode()
      useEditorStore.getState().layFlatObject(id, { normal: [1, 0, 0], boundsMin: [9, 18, 27], boundsMax: [11, 22, 33] })
    })
    const props = transformSpy.mock.calls.at(-1)![0]
    const mesh = container.querySelector('mesh')!
    expect(props.object.current).toBe(mesh)
    const position = mesh.getAttribute('position')!.split(',').map(Number)
    expect(position[0]).toBeCloseTo(15)
    expect(position[1]).toBeCloseTo(1)
    expect(position[2]).toBeCloseTo(39)
  })

  it('uses prepared neighboring surfaces only at drag release', async () => {
    parseAsyncSpy.mockImplementation(() => Promise.resolve(fakeGltfScene(new BoxGeometry(50.8,20,50.8))))
    seedPreviewableAsset('asset-1')
    const target = useEditorStore.getState().insert('asset-1')
    const moving = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().setTranslation(target,[0,10,0])
    useEditorStore.getState().setTranslation(moving,[52,10,0])
    useEditorStore.getState().toggleSnap()
    render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())
    await act(async () => { await Promise.resolve() })
    const props = transformSpy.mock.calls.at(-1)![0]
    props.object.current.position = { toArray: () => [51.5,10,0] }
    props.object.current.quaternion = { toArray: () => [0,0,0,1] }
    const revision = useEditorStore.getState().revision
    act(() => props.onMouseDown())
    expect(useEditorStore.getState().objects[1].translationMm[0]).toBe(52)
    expect(useEditorStore.getState().revision).toBe(revision)
    act(() => props.onMouseUp())
    expect(useEditorStore.getState().objects[1].translationMm[0]).toBeCloseTo(50.8)
    expect(useEditorStore.getState().revision).toBe(revision+1)
    expect(useEditorStore.getState().snapFeedback).toBe('Faces and nearest edges aligned')
  })

  it('fetches the published GLB preview for a scene object and parses the exact fetched bytes for rendering', async () => {
    const buffer = new ArrayBuffer(8)
    fetchAssetPreviewMock.mockResolvedValue(buffer)
    seedPreviewableAsset('asset-1')
    useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() => expect(fetchAssetPreviewMock).toHaveBeenCalledWith('asset-1'))
    await waitFor(() => expect(parseAsyncSpy).toHaveBeenCalledWith(buffer, ''))
  })

  it('never fetches the raw original.stl bytes for viewport rendering', async () => {
    seedPreviewableAsset('asset-1')
    useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() => expect(fetchAssetPreviewMock).toHaveBeenCalled())
    for (const call of fetchAssetPreviewMock.mock.calls) {
      expect(String(call[0])).not.toMatch(/original/)
    }
  })

  it('extracts the first Mesh geometry out of the parsed GLTF scene graph', async () => {
    const geometry = { isBufferGeometry: true }
    parseAsyncSpy.mockResolvedValueOnce(fakeGltfScene(geometry))
    seedPreviewableAsset('asset-1')
    useEditorStore.getState().insert('asset-1')

    const { container } = render(<EditorCanvas />)

    await waitFor(() => expect(container.querySelector('mesh')).not.toBeNull())
  })

  it('uses a matte clay material to make model relief visible', async () => {
    seedPreviewableAsset('asset-1')
    useEditorStore.getState().insert('asset-1')

    const { container } = render(<EditorCanvas />)

    await waitFor(() => expect(container.querySelector('meshstandardmaterial')).not.toBeNull())
    const material = container.querySelector('meshstandardmaterial')
    expect(material).toHaveAttribute('color', '#9b7358')
    expect(material).toHaveAttribute('roughness', '0.88')
    expect(material).toHaveAttribute('metalness', '0')
  })

  it('never fetches a preview and sets a scoped "no preview available" error when the asset has no preview', async () => {
    useEditorStore
      .getState()
      .setAssets([{ id: 'asset-1', processingStatus: 'READY', previewAvailable: false, originalFilename: null }])
    const id = useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() =>
      expect(useEditorStore.getState().objectGeometryErrors[id]).toBe('No preview available for this object.'),
    )
    expect(fetchAssetPreviewMock).not.toHaveBeenCalled()
    expect(useEditorStore.getState().error).toBeNull()
  })

  it('never fetches a preview and sets a scoped error when the object references an asset not present in the catalog', async () => {
    const id = useEditorStore.getState().insert('asset-unknown')

    render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().objectGeometryErrors[id]).not.toBeUndefined())
    expect(fetchAssetPreviewMock).not.toHaveBeenCalled()
    expect(useEditorStore.getState().error).toBeNull()
  })

  it('sets only that object\'s scoped error on a rejected preview fetch, never the global error', async () => {
    fetchAssetPreviewMock.mockRejectedValue(new Error('network down'))
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().objectGeometryErrors[id]).toBe('Failed to load object geometry.'))
    expect(parseAsyncSpy).not.toHaveBeenCalled()
    expect(useEditorStore.getState().error).toBeNull()
  })

  it('sets only that object\'s scoped error on a GLTFLoader.parseAsync failure, never the global error', async () => {
    parseAsyncSpy.mockRejectedValueOnce(new Error('malformed glb'))
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().objectGeometryErrors[id]).toBe('Failed to load object geometry.'))
    expect(useEditorStore.getState().error).toBeNull()
  })

  it('scopes a failure to only the failing object when one of two objects fails and the other succeeds', async () => {
    useEditorStore.getState().setAssets([
      { id: 'asset-good', processingStatus: 'READY', previewAvailable: true, originalFilename: null },
      { id: 'asset-bad', processingStatus: 'READY', previewAvailable: true, originalFilename: null },
    ])
    fetchAssetPreviewMock.mockImplementation((assetId: string) =>
      assetId === 'asset-bad' ? Promise.reject(new Error('boom')) : Promise.resolve(new ArrayBuffer(0)),
    )
    const goodId = useEditorStore.getState().insert('asset-good')
    const badId = useEditorStore.getState().insert('asset-bad')

    render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().objectGeometryErrors[badId]).toBe('Failed to load object geometry.'))
    expect(useEditorStore.getState().objectGeometryErrors[goodId]).toBeUndefined()
    expect(useEditorStore.getState().error).toBeNull()
  })

  it('clears a scoped error once a later retry succeeds', async () => {
    fetchAssetPreviewMock.mockRejectedValueOnce(new Error('network down'))
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().objectGeometryErrors[id]).toBe('Failed to load object geometry.'))

    fetchAssetPreviewMock.mockResolvedValueOnce(new ArrayBuffer(8))
    act(() => {
      useEditorStore.getState().retryObjectGeometry(id)
    })

    await waitFor(() => expect(useEditorStore.getState().objectGeometryErrors[id]).toBeUndefined())
    expect(useEditorStore.getState().error).toBeNull()
  })

  it('disables orbit controls while a transform control drag is active, and restores it after', async () => {
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)

    const { container } = render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())
    stubMeshTransform(container)

    const props = transformSpy.mock.calls.at(-1)?.[0] as { onMouseDown: () => void; onMouseUp: () => void }
    props.onMouseDown()
    expect(useEditorStore.getState().orbitEnabled).toBe(false)

    props.onMouseUp()
    expect(useEditorStore.getState().orbitEnabled).toBe(true)
  })

  it('defers persistence until the drag ends so keyboard movement remains stable', async () => {
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)

    const { container } = render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())
    stubMeshTransform(container, [10, 20, 30], [0, 0, 0, 1])

    const props = transformSpy.mock.calls.at(-1)?.[0] as {
      onObjectChange?: () => void
      onMouseUp: () => void
    }

    // Intermediate deltas (fired on every pointermove while dragging) must never touch the
    // store: committing on each one triggers a re-render that recreates the TransformControls
    // children reference, which makes the underlying library detach/reattach mid-drag and
    // permanently loses its drag axis — the object stops responding for the rest of that drag
    // and, because "axis" also gates the mouseUp dispatch, orbit controls never re-enable.
    props.onObjectChange?.()
    props.onObjectChange?.()
    expect(useEditorStore.getState().objects[0].translationMm).toEqual([0, 0, 0])

    props.onMouseUp()
    expect(useEditorStore.getState().objects[0].translationMm).toEqual([10, 20, 30])
  })

  it('adds a decorative grid to the viewport without introducing an extra ground mesh', async () => {
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)

    const { container } = render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())

    expect(container.querySelectorAll('gridHelper').length).toBeGreaterThan(0)
    expect(container.querySelectorAll('mesh')).toHaveLength(1)
  })

  it('keeps the transform gizmo attached to the same children when an unrelated store field changes mid-drag', async () => {
    // Regression for the still-reported "camera sometimes stays locked" bug: TransformControls'
    // real attach effect (drei) depends on `children` identity (see node_modules source) and
    // detaches/reattaches — clearing its tracked drag axis — whenever that identity changes.
    // EditorObjectMesh is a plain (non-memoized) function component, so ANY re-render of its
    // parent EditorCanvas re-executes it and gives `mesh` a new element identity, even when
    // neither `object` nor `selectedId`/`mode` changed. `onMouseDown` itself triggers exactly
    // such an unrelated re-render by flipping `orbitEnabled`, which EditorCanvas subscribes to.
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)

    render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())

    const callsBefore = transformSpy.mock.calls.length
    const childrenBefore = transformSpy.mock.calls.at(-1)?.[0].children

    act(() => {
      useEditorStore.getState().setDragging(true)
    })

    expect(transformSpy.mock.calls.length).toBe(callsBefore)
    expect(transformSpy.mock.calls.at(-1)?.[0].children).toBe(childrenBefore)
  })

  it('wires the active transform control mode to the store transform mode', async () => {
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)
    useEditorStore.getState().setMode('rotate')

    render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())

    const props = transformSpy.mock.calls.at(-1)?.[0] as { mode: string }
    expect(props.mode).toBe('rotate')
  })

  it('renders a locatable, clickable placeholder mesh for an object with a geometry error instead of nothing', async () => {
    useEditorStore
      .getState()
      .setAssets([{ id: 'asset-1', processingStatus: 'READY', previewAvailable: false, originalFilename: null }])
    const id = useEditorStore.getState().insert('asset-1')

    const { container } = render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().objectGeometryErrors[id]).not.toBeUndefined())
    const mesh = container.querySelector('mesh')
    expect(mesh).not.toBeNull()
  })

  it('renders an accessible geometry error indicator with a Retry geometry action', async () => {
    useEditorStore
      .getState()
      .setAssets([{ id: 'asset-1', processingStatus: 'READY', previewAvailable: false, originalFilename: null }])
    const id = useEditorStore.getState().insert('asset-1')

    const { getByRole } = render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().objectGeometryErrors[id]).toBeDefined())
    expect(getByRole('alert')).toHaveTextContent('Geometry unavailable')
    const retry = getByRole('button', { name: 'Retry geometry' })

    act(() => retry.click())
    expect(useEditorStore.getState().geometryRetryTick[id]).toBe(1)
  })

  it('renders six clickable support-zone overlays while lay-flat mode is active', async () => {
    parseAsyncSpy.mockImplementationOnce(() =>
      Promise.resolve(fakeGltfScene({ boundingBox: new Box3(new Vector3(-10, -20, -30), new Vector3(10, 20, 30)) })),
    )
    seedPreviewableAsset('asset-1')
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().setTranslation(id, [10, 20, 30])
    useEditorStore.getState().setRotation(id, [0, 0.7071068, 0, 0.7071068])
    useEditorStore.getState().select(id)
    const { container } = render(<EditorCanvas />)
    await waitFor(() => expect(container.querySelector('meshstandardmaterial')).not.toBeNull())
    act(() => useEditorStore.getState().toggleLayFlatMode())
    expect(container.querySelectorAll('planeGeometry')).toHaveLength(6)
    const overlayGroup = Array.from(container.querySelectorAll('group')).find((group) => group.hasAttribute('position'))!
    expect(overlayGroup).toHaveAttribute('position', '10,20,30')
    expect(overlayGroup).toHaveAttribute('quaternion', '0,0.7071068,0,0.7071068')
    expect(overlayGroup).toHaveAttribute('scale', '1,1,1')
    const zones = overlayGroup.querySelectorAll('mesh')
    expect(zones[0]).toHaveAttribute('position', '0,20.5,0')
    expect(zones[1]).toHaveAttribute('position', '0,-20.5,0')
    expect(zones[4]).toHaveAttribute('position', '0,0,30.5')
    expect(zones[5]).toHaveAttribute('position', '0,0,-30.5')
    expect(zones[0].querySelector('planegeometry')).toHaveAttribute('args', '20,60')
    expect(zones[2].querySelector('planegeometry')).toHaveAttribute('args', '60,40')
  })

  // This harness has no real R3F scene graph behind its mocked <group> (see the `instanceof
  // Object3D` guard note above), so it can only exercise the default-view branch of the fit
  // effect — not framing a real bounding box, which frameBox's own unit tests already cover.
  it('resets the camera and orbit target to the default view when a fit-to-scene is requested', async () => {
    render(<EditorCanvas />)
    await waitFor(() => expect(orbitSpy).toHaveBeenCalled())

    fakeCamera.position.set(123, 456, 789)
    fakeControls.target.set(1, 2, 3)

    act(() => {
      useEditorStore.getState().requestFitToScene()
    })

    expect(fakeCamera.position.toArray()).toEqual([0, 500, 500])
    expect(fakeControls.target.toArray()).toEqual([0, 0, 0])
    expect(fakeControls.update).toHaveBeenCalledTimes(1)
  })

  it('never fits the scene on initial mount, only on an explicit requestFitToScene() call', async () => {
    render(<EditorCanvas />)
    await waitFor(() => expect(orbitSpy).toHaveBeenCalled())

    expect(fakeControls.update).not.toHaveBeenCalled()
  })
})
