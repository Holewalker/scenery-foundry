import { Box3, Matrix3, Matrix4, Quaternion, Vector3 } from 'three'
import type { Vec3, Vec4 } from './store'

export interface Surface { normal: Vec3; center: Vec3; area: number; edges: [Vec3, Vec3][] }
export interface SurfaceData { faces: Surface[]; min: Vec3; max: Vec3; limited: boolean }
export interface SnapObject { id: number; translation: Vec3; quaternion: Vec4; scale: Vec3; surfaces: SurfaceData }
export interface SnapResult { translation: Vec3; quaternion: Vec4; targetId: number | 'ground'; sourceFace: number; targetFace: number; distance: number }
const MAX_FACES = 24
const MAX_EDGES = 16
const CAPTURE_MM = 5
const MAX_ANGLE = Math.PI / 6
const vec = (v: Vec3) => new Vector3(...v)
const key = (p: Vector3) => p.toArray().map(n => Math.round(n * 10000)).join(',')

/** Merge only touching collinear boundary segments, never bridge a hole or a disconnected edge. */
function straightEdges(boundary: [Vec3, Vec3][]): [Vec3, Vec3][] {
  const groups = new Map<string, [Vec3, Vec3][]>()
  for (const edge of boundary) {
    const direction = vec(edge[1]).sub(vec(edge[0])).normalize()
    const components = direction.toArray()
    if ((components.find(v => Math.abs(v) > 1e-6) ?? 1) < 0) direction.negate()
    const line = `${key(direction)}|${key(vec(edge[0]).cross(direction))}`
    const group = groups.get(line) ?? []
    group.push(edge)
    groups.set(line, group)
  }
  const result: [Vec3, Vec3][] = []
  for (const edges of groups.values()) {
    const byPoint = new Map<string, number[]>()
    edges.forEach((edge, i) => edge.forEach(p => { const k = key(vec(p)); const list = byPoint.get(k) ?? []; list.push(i); byPoint.set(k, list) }))
    const visited = new Set<number>()
    edges.forEach((edge, i) => {
      if (visited.has(i)) return
      const direction = vec(edge[1]).sub(vec(edge[0])).normalize()
      let low = edge[0], high = edge[0], min = vec(low).dot(direction), max = min
      const pending = [i]
      visited.add(i)
      while (pending.length) {
        for (const point of edges[pending.pop()!]) {
          const distance = vec(point).dot(direction)
          if (distance < min) { min = distance; low = point }
          if (distance > max) { max = distance; high = point }
          for (const next of byPoint.get(key(vec(point)))!) if (!visited.has(next)) { visited.add(next); pending.push(next) }
        }
      }
      result.push([low, high])
    })
  }
  return result
}

/** O(triangles) bounded preprocessing, intended for the geometry worker, not pointer events. */
export function extractSurfaces(positions: ArrayLike<number>, indices?: ArrayLike<number>, maxTriangles = 500000): SurfaceData {
  const count = Math.floor((indices?.length ?? positions.length / 3) / 3)
  if (count > maxTriangles) return { faces: [], min: [0,0,0], max: [0,0,0], limited: true }
  const bounds = new Box3()
  for (let i = 0; i < positions.length; i += 3) bounds.expandByPoint(new Vector3(positions[i], positions[i + 1], positions[i + 2]))
  const empty: SurfaceData = { faces: [], min: bounds.min.toArray(), max: bounds.max.toArray(), limited: count > maxTriangles }
  if (empty.limited || bounds.isEmpty()) return empty
  const parents = new Int32Array(count).fill(-1)
  const normals = new Float64Array(count * 3)
  const centers = new Float64Array(count * 3)
  const areas = new Float64Array(count)
  // Store only encoded triangle/side references for boundaries, not duplicated coordinate objects.
  const edges = new Map<string, number>()
  const planes = new Map<string, number>()
  const vertex = (triangle: number, corner: number) => {
    const i = (indices ? indices[triangle * 3 + corner] : triangle * 3 + corner) * 3
    return new Vector3(positions[i], positions[i + 1], positions[i + 2])
  }
  const root = (id: number): number => { while (parents[id] !== id) { parents[id] = parents[parents[id]]; id = parents[id] } return id }
  for (let triangle = 0; triangle < count; triangle++) {
    const vertices = [vertex(triangle, 0), vertex(triangle, 1), vertex(triangle, 2)]
    const [a,b,c] = vertices
    const normal = b.clone().sub(a).cross(c.clone().sub(a))
    const area = normal.length() / 2
    if (!Number.isFinite(area) || area < 1e-8) continue
    normal.normalize()
    const planeKey = `${key(normal)}:${Math.round(normal.dot(a) * 1000)}`
    let plane = planes.get(planeKey)
    if (plane === undefined) { plane = planes.size; planes.set(planeKey, plane) }
    parents[triangle] = triangle
    normals.set(normal.toArray(), triangle * 3)
    centers.set(a.clone().add(b).add(c).multiplyScalar(area / 3).toArray(), triangle * 3)
    areas[triangle] = area
    for (let side = 0; side < 3; side++) {
      const edgeKey = `${plane}|${[key(vertices[side]), key(vertices[(side + 1) % 3])].sort().join('|')}`
      const previous = edges.get(edgeKey)
      if (previous !== undefined) { parents[root(triangle)] = root(Math.floor(previous / 3)); edges.delete(edgeKey) }
      else edges.set(edgeKey, triangle * 3 + side)
    }
  }
  planes.clear()
  const totalAreas = new Float64Array(count)
  const totalCenters = new Float64Array(count * 3)
  for (let i = 0; i < count; i++) if (parents[i] >= 0) {
    const r = root(i)
    totalAreas[r] += areas[i]
    for (let axis = 0; axis < 3; axis++) totalCenters[r * 3 + axis] += centers[i * 3 + axis]
  }
  const candidates: number[] = []
  for (let i = 0; i < count; i++) if (totalAreas[i] > 0.01) candidates.push(i)
  candidates.sort((a,b) => totalAreas[b] - totalAreas[a])
  const patches = new Map<number, Surface>()
  for (const r of candidates.slice(0, MAX_FACES)) patches.set(r, {
    normal: [normals[r*3], normals[r*3+1], normals[r*3+2]],
    center: [totalCenters[r*3]/totalAreas[r], totalCenters[r*3+1]/totalAreas[r], totalCenters[r*3+2]/totalAreas[r]],
    area: totalAreas[r], edges: [],
  })
  for (const encoded of edges.values()) {
    const triangle = Math.floor(encoded / 3), side = encoded % 3
    const patch = patches.get(root(triangle))
    if (patch) patch.edges.push([vertex(triangle, side).toArray(), vertex(triangle, (side+1)%3).toArray()])
  }
  const faces = [...patches.values()].filter(p => p.edges.length >= 3).map(p => ({ ...p,
    edges: straightEdges(p.edges).sort((a,b) => vec(b[0]).distanceToSquared(vec(b[1])) - vec(a[0]).distanceToSquared(vec(a[1]))).slice(0, MAX_EDGES) }))
  return { ...empty, faces }
}

function matrix(object: SnapObject, quaternion = object.quaternion, translation = object.translation): Matrix4 {
  return new Matrix4().compose(vec(translation), new Quaternion(...quaternion), vec(object.scale))
}
function worldFace(face: Surface, transform: Matrix4): Surface {
  const normalMatrix = new Matrix3().getNormalMatrix(transform)
  return { ...face, normal: vec(face.normal).applyMatrix3(normalMatrix).normalize().toArray(),
    center: vec(face.center).applyMatrix4(transform).toArray(), edges: face.edges.map(([a,b]) => [vec(a).applyMatrix4(transform).toArray(), vec(b).applyMatrix4(transform).toArray()]) }
}
function box(object: SnapObject, transform = matrix(object)): Box3 {
  return new Box3(vec(object.surfaces.min), vec(object.surfaces.max)).applyMatrix4(transform)
}
function rotatedTranslation(object: SnapObject, q: Quaternion): Vec3 {
  const center = vec(object.surfaces.min).add(vec(object.surfaces.max)).multiplyScalar(0.5).multiply(vec(object.scale))
  return vec(object.translation).add(center.clone().applyQuaternion(new Quaternion(...object.quaternion))).sub(center.applyQuaternion(q)).toArray()
}

function closestPoint(point: Vec3, edge: [Vec3, Vec3]): Vector3 {
  const a = vec(edge[0]), direction = vec(edge[1]).sub(a)
  const t = Math.max(0, Math.min(1, vec(point).sub(a).dot(direction) / direction.lengthSq()))
  return a.addScaledVector(direction, t)
}
function edgeDeltas(source: [Vec3, Vec3], target: [Vec3, Vec3]): Vector3[] {
  return [...source.map(p => closestPoint(p, target).sub(vec(p))), ...target.map(p => vec(p).sub(closestPoint(p, source)))]
}

/** Align neighboring planar boundaries; free space never falls back to center-grid rounding. */
export function solveFaceSnap(moving: SnapObject, targets: SnapObject[]): SnapResult | null {
  if (moving.surfaces.limited || !moving.surfaces.faces.length || moving.scale.some(v => v <= 0)) return null
  const originalQ = new Quaternion(...moving.quaternion)
  const movingBox = box(moving)
  const nearby = targets.filter(t => t.id !== moving.id && !t.surfaces.limited && t.scale.every(v => v > 0) && box(t).intersectsBox(movingBox.clone().expandByScalar(CAPTURE_MM)))
    .sort((a,b) => box(a).getCenter(new Vector3()).distanceToSquared(movingBox.getCenter(new Vector3()))
      - box(b).getCenter(new Vector3()).distanceToSquared(movingBox.getCenter(new Vector3())) || a.id - b.id).slice(0, 8)
  let best: SnapResult | null = null
  let bestScore = Infinity
  let attempts = 0
  for (const target of nearby) {
    const targetTransform = matrix(target)
    for (let si = 0; si < moving.surfaces.faces.length; si++) {
      const source = moving.surfaces.faces[si]
      const originalFace = worldFace(source, matrix(moving))
      for (let ti = 0; ti < target.surfaces.faces.length; ti++) {
        const destination = worldFace(target.surfaces.faces[ti], targetTransform)
        const normal = vec(destination.normal)
        const angle = vec(originalFace.normal).angleTo(normal.clone().negate())
        if (angle > MAX_ANGLE) continue
        if (Math.abs(vec(originalFace.center).sub(vec(destination.center)).dot(normal)) > CAPTURE_MM) continue
        const normalCorrection = new Quaternion().setFromUnitVectors(vec(originalFace.normal), normal.clone().negate())
        const alignedQ = normalCorrection.multiply(originalQ).normalize()
        const alignedTranslation = rotatedTranslation(moving, alignedQ)
        const aligned = worldFace(source, matrix(moving, alignedQ.toArray(), alignedTranslation))
        // Rank the real boundary pairs first: bounded evaluation follows nearest edges, not triangle order.
        const pairs = aligned.edges.flatMap((edge, ei) => destination.edges.map((other, oi) => ({ ei, oi,
          distance: Math.min(...edgeDeltas(edge, other).map(delta => delta.length())) })))
          .filter(pair => pair.distance <= CAPTURE_MM * 2).sort((a,b) => a.distance-b.distance).slice(0, 8)
        for (const pair of pairs) {
          if (++attempts > 2048) break
          const [sa,sb] = aligned.edges[pair.ei]
          const [ta,tb] = destination.edges[pair.oi]
          const sourceDirection = vec(sb).sub(vec(sa)).normalize()
          const targetDirection = vec(tb).sub(vec(ta)).normalize()
          if (sourceDirection.dot(targetDirection) < 0) targetDirection.negate()
          const twistAngle = Math.atan2(normal.dot(sourceDirection.clone().cross(targetDirection)), sourceDirection.dot(targetDirection))
          const q = new Quaternion().setFromAxisAngle(normal, twistAngle).multiply(alignedQ).normalize()
          const rotation = q.angleTo(originalQ)
          if (rotation > MAX_ANGLE) continue
          const translation = rotatedTranslation(moving, q)
          const corrected = worldFace(source, matrix(moving, q.toArray(), translation))
          for (const delta of edgeDeltas(corrected.edges[pair.ei], [ta,tb])) {
            if (delta.length() > CAPTURE_MM) continue
            const next = vec(translation).add(delta).toArray() as Vec3
            // Reject below-ground placements. Contact is face/edge snapping, not a general collision solver.
            const candidateBounds = box(moving, matrix(moving, q.toArray(), next))
            if (candidateBounds.min.y < -0.01) continue
            const score = delta.length() + rotation * 10
            if (score >= bestScore - 1e-8) continue
            bestScore = score
            best = { translation: next, quaternion: q.toArray(), targetId: target.id, sourceFace: si, targetFace: ti, distance: delta.length() }
          }
        }
      }
    }
  }
  if (best) return best
  // Ground is a plane, not a center-grid point: preserve horizontal pivot position and correct height.
  for (let si = 0; si < moving.surfaces.faces.length; si++) {
    const source = moving.surfaces.faces[si]
    const face = worldFace(source, matrix(moving))
    const down = new Vector3(0,-1,0)
    if (vec(face.normal).angleTo(down) > MAX_ANGLE || Math.abs(face.center[1]) > CAPTURE_MM) continue
    const q = new Quaternion().setFromUnitVectors(vec(face.normal), down).multiply(originalQ).normalize()
    const translation = rotatedTranslation(moving, q)
    const corrected = worldFace(source, matrix(moving, q.toArray(), translation))
    const next: Vec3 = [translation[0], translation[1] - corrected.center[1], translation[2]]
    if (box(moving, matrix(moving, q.toArray(), next)).min.y < -0.01) continue
    const score = Math.abs(corrected.center[1]) + q.angleTo(originalQ)*10
    if (score >= bestScore) continue
    bestScore = score
    best = { translation: next, quaternion: q.toArray(), targetId: 'ground', sourceFace: si, targetFace: -1, distance: Math.abs(corrected.center[1]) }
  }
  return best
}
