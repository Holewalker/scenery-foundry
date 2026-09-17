import type { BufferGeometry } from 'three'
import { extractSurfaces } from './faceSnap'
import type { SurfaceData } from './faceSnap'

// One worker serializes expensive preprocessing; pointer events only inspect the bounded result.
let worker: Worker | null = null
let nextId = 0
const pending = new Map<number, (data: SurfaceData | null) => void>()
const cache = new WeakMap<BufferGeometry, Promise<SurfaceData | null>>()
export function prepareSurfaces(geometry: BufferGeometry): Promise<SurfaceData | null> {
  const cached = cache.get(geometry)
  if (cached) return cached
  const task = new Promise<SurfaceData | null>((resolve) => {
    const attribute = geometry.getAttribute('position')
    if (!attribute) { resolve(null); return }
    const triangleCount = (geometry.getIndex()?.count ?? attribute.count) / 3
    if (triangleCount > 500000) { resolve({ faces: [], min: [0,0,0], max: [0,0,0], limited: true }); return }
    const positions = new Float32Array(attribute.count * 3)
    for (let i = 0; i < attribute.count; i++) positions.set([attribute.getX(i), attribute.getY(i), attribute.getZ(i)], i * 3)
    const index = geometry.getIndex()
    const indices = index ? new Uint32Array(index.array) : undefined
    // The test runtime has no Worker. Production failure degrades to unsnapped movement, not UI blocking.
    if (typeof Worker === 'undefined') { resolve(import.meta.env.MODE === 'test' ? extractSurfaces(positions, indices) : null); return }
    try {
      if (!worker) {
        worker = new Worker(new URL('./faceSnap.worker.ts', import.meta.url), { type: 'module' })
        worker.onmessage = (event: MessageEvent<{ id: number; surfaces: SurfaceData | null }>) => {
          pending.get(event.data.id)?.(event.data.surfaces)
          pending.delete(event.data.id)
        }
        worker.onerror = () => {
          for (const finish of pending.values()) finish(null)
          pending.clear()
          worker?.terminate()
          worker = null
        }
      }
      const id = nextId++
      pending.set(id, resolve)
      worker.postMessage({ id, positions, indices }, indices ? [positions.buffer, indices.buffer] : [positions.buffer])
    } catch { resolve(null) }
  })
  cache.set(geometry, task)
  return task
}
