import { act, render, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './store'
import { EditorCanvas } from './EditorCanvas'

const fetchAssetPreviewMock = vi.fn()
vi.mock('../api/client', () => ({ fetchAssetPreview: (...args: unknown[]) => fetchAssetPreviewMock(...args) }))

// Fakes the GLTF scene graph shape a real GLTFLoader.parseAsync would resolve: a root scene
// Group whose descendants include exactly one Mesh (matching trimesh's plain, un-nested export).
function fakeGltfScene(geometry: unknown) {
  const mesh = { isMesh: true, geometry }
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
vi.mock('@react-three/fiber', () => ({
  Canvas: ({ children }: { children: ReactNode }) => <>{children}</>,
}))
vi.mock('@react-three/drei', () => ({
  OrbitControls: (props: Record<string, unknown>) => {
    orbitSpy(props)
    return null
  },
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

describe('EditorCanvas', () => {
  it('fetches the published GLB preview for a scene object and parses the exact fetched bytes for rendering', async () => {
    const buffer = new ArrayBuffer(8)
    fetchAssetPreviewMock.mockResolvedValue(buffer)
    useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() => expect(fetchAssetPreviewMock).toHaveBeenCalledWith('asset-1'))
    await waitFor(() => expect(parseAsyncSpy).toHaveBeenCalledWith(buffer, ''))
  })

  it('never fetches the raw original.stl bytes for viewport rendering', async () => {
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
    useEditorStore.getState().insert('asset-1')

    const { container } = render(<EditorCanvas />)

    await waitFor(() => expect(container.querySelector('mesh')).not.toBeNull())
  })

  it('routes a rejected preview fetch into the editor error state instead of an unhandled rejection', async () => {
    fetchAssetPreviewMock.mockRejectedValue(new Error('network down'))
    useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().error).not.toBeNull())
    expect(parseAsyncSpy).not.toHaveBeenCalled()
  })

  it('routes a GLTFLoader.parseAsync failure into the editor error state', async () => {
    parseAsyncSpy.mockRejectedValueOnce(new Error('malformed glb'))
    useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas />)

    await waitFor(() => expect(useEditorStore.getState().error).not.toBeNull())
  })

  it('disables orbit controls while a transform control drag is active, and restores it after', async () => {
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

  it('defers committing the dragged transform to the store until the drag ends, never on an intermediate change', async () => {
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
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)
    useEditorStore.getState().setMode('rotate')

    render(<EditorCanvas />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())

    const props = transformSpy.mock.calls.at(-1)?.[0] as { mode: string }
    expect(props.mode).toBe('rotate')
  })
})
