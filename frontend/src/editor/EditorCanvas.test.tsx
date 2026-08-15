import { render, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { resetEditorStore, useEditorStore } from './store'
import { EditorCanvas } from './EditorCanvas'

const fetchAssetStlMock = vi.fn()
vi.mock('../api/client', () => ({ fetchAssetStl: (...args: unknown[]) => fetchAssetStlMock(...args) }))

const parseSpy = vi.fn((buffer: unknown) => ({ buffer }))
vi.mock('three/examples/jsm/loaders/STLLoader.js', () => ({
  STLLoader: class {
    parse(buffer: unknown) {
      return parseSpy(buffer)
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
  fetchAssetStlMock.mockReset().mockResolvedValue(new ArrayBuffer(0))
  orbitSpy.mockReset()
  transformSpy.mockReset()
  parseSpy.mockClear()
})

describe('EditorCanvas', () => {
  it('fetches the authenticated STL bytes for a scene object and parses the exact fetched bytes for rendering', async () => {
    const buffer = new ArrayBuffer(8)
    fetchAssetStlMock.mockResolvedValue(buffer)
    useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas projectId="project-1" />)

    await waitFor(() => expect(fetchAssetStlMock).toHaveBeenCalledWith('project-1', 'asset-1'))
    await waitFor(() => expect(parseSpy).toHaveBeenCalledWith(buffer))
  })

  it('routes a rejected STL fetch into the editor error state instead of an unhandled rejection', async () => {
    fetchAssetStlMock.mockRejectedValue(new Error('network down'))
    useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas projectId="project-1" />)

    await waitFor(() => expect(useEditorStore.getState().error).not.toBeNull())
    expect(parseSpy).not.toHaveBeenCalled()
  })

  it('routes an STLLoader.parse failure into the editor error state', async () => {
    parseSpy.mockImplementationOnce(() => {
      throw new Error('malformed geometry')
    })
    useEditorStore.getState().insert('asset-1')

    render(<EditorCanvas projectId="project-1" />)

    await waitFor(() => expect(useEditorStore.getState().error).not.toBeNull())
  })

  it('disables orbit controls while a transform control drag is active, and restores it after', async () => {
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)

    render(<EditorCanvas projectId="project-1" />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())

    const props = transformSpy.mock.calls.at(-1)?.[0] as { onMouseDown: () => void; onMouseUp: () => void }
    props.onMouseDown()
    expect(useEditorStore.getState().orbitEnabled).toBe(false)

    props.onMouseUp()
    expect(useEditorStore.getState().orbitEnabled).toBe(true)
  })

  it('wires the active transform control mode to the store transform mode', async () => {
    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().select(id)
    useEditorStore.getState().setMode('rotate')

    render(<EditorCanvas projectId="project-1" />)
    await waitFor(() => expect(transformSpy).toHaveBeenCalled())

    const props = transformSpy.mock.calls.at(-1)?.[0] as { mode: string }
    expect(props.mode).toBe('rotate')
  })
})
