import { extractSurfaces } from './faceSnap'
self.onmessage = (event: MessageEvent<{ id: number; positions: Float32Array; indices?: Uint32Array }>) => {
  const { id, positions, indices } = event.data
  try { self.postMessage({ id, surfaces: extractSurfaces(positions, indices) }) }
  catch { self.postMessage({ id, surfaces: null }) }
}
