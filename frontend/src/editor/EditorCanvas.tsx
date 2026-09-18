import { Canvas, useThree } from '@react-three/fiber'
import { Html, OrbitControls, TransformControls } from '@react-three/drei'
import { createContext, memo, useContext, useEffect, useMemo, useRef, useState } from 'react'
import type { BufferGeometry, Group, Mesh, Object3D, PerspectiveCamera } from 'three'
import { Box3, Object3D as Object3DClass, Vector3 } from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib'
import { fetchAssetPreview } from '../api/client'
import { frameBox } from './cameraFit'
import { supportNormalFromGeometry } from './layFlat'
import { intersectHorizontalDragPlane } from './directDrag'
import { centeredGeometry, pivotPosition } from './objectPivot'
import { prepareSurfaces } from './surfacePreparation'
import type { SurfaceData, SnapObject } from './faceSnap'
const SnapSurfaces = createContext<Map<number, SurfaceData> | null>(null)
import type { EditorObject, Vec3, Vec4 } from './store'
import { useEditorStore } from './store'

// GLTFLoader.parseAsync resolves a full scene graph (unlike STLLoader.parse's single
// BufferGeometry), so the editor keeps rendering a single <mesh> by extracting the first
// Mesh found anywhere in that graph — matching the worker's plain, un-nested GLB export.
function firstMeshGeometry(root: Object3D): BufferGeometry | null {
  let found: Mesh | null = null
  root.traverse((child) => {
    if (!found && (child as Mesh).isMesh) found = child as Mesh
  })
  return found ? (found as Mesh).geometry : null
}

function visibleMeshBounds(root: Object3D, selectedId: number | null = null): Box3 | null {
  const bounds = new Box3()
  let found = false
  root.traverse((child) => {
    const mesh = child as Mesh
    if (!mesh.isMesh || !mesh.visible || !mesh.geometry) return
    if (selectedId !== null && mesh.userData.editorObjectId !== selectedId) return
    const meshBounds = new Box3().setFromObject(mesh)
    if (meshBounds.isEmpty()) return
    const values = [...meshBounds.min.toArray(), ...meshBounds.max.toArray()]
    if (!values.every(Number.isFinite)) return
    bounds.union(meshBounds)
    found = true
  })
  return found ? bounds : null
}

// Errors are scoped per object (store.objectGeometryErrors), never the global `error` field: one
// broken object must never blank out the whole editor with a banner that never clears itself when
// that object later recovers. `retryTick` (bumped by store.retryObjectGeometry) is in the effect's
// deps purely to force a re-run — its value itself is never read.
function useObjectGeometry(assetId: string, objectId: number): BufferGeometry | null {
  const [geometry, setGeometry] = useState<BufferGeometry | null>(null)
  const setObjectGeometryError = useEditorStore((state) => state.setObjectGeometryError)
  const previewAvailable = useEditorStore(
    (state) => state.assets.find((asset) => asset.id === assetId)?.previewAvailable === true,
  )
  const retryTick = useEditorStore((state) => state.geometryRetryTick[objectId] ?? 0)

  useEffect(() => {
    if (!previewAvailable) {
      setGeometry(null)
      setObjectGeometryError(objectId, 'No preview available for this object.')
      return
    }
    let cancelled = false
    fetchAssetPreview(assetId)
      .then((buffer) => new GLTFLoader().parseAsync(buffer, ''))
      .then((gltf) => {
        if (cancelled) return
        const meshGeometry = firstMeshGeometry(gltf.scene)
        if (!meshGeometry) throw new Error('preview.glb scene graph contains no mesh')
        meshGeometry.computeBoundingBox()
        setGeometry(meshGeometry)
        setObjectGeometryError(objectId, null)
      })
      .catch(() => {
        if (!cancelled) setObjectGeometryError(objectId, 'Failed to load object geometry.')
      })
    return () => {
      cancelled = true
    }
  }, [assetId, objectId, previewAvailable, retryTick, setObjectGeometryError])
  return geometry
}

// Memoized so a re-render of EditorCanvas from an UNRELATED store field (most notably
// `orbitEnabled`, flipped by TransformControls' own onMouseDown at the start of every drag)
// does not re-execute this component. Without this, `mesh` below gets a new element identity
// on every such render, and drei's TransformControls attach effect (keyed on `children`
// identity, see @react-three/drei/core/TransformControls.js) detaches and reattaches the real
// three-stdlib controls — clearing its tracked drag axis mid-gesture and, since that axis also
// gates the mouseUp dispatch, intermittently leaving orbit controls disabled after mouseup.
const EditorObjectMesh = memo(function EditorObjectMesh({ object }: { object: EditorObject }) {
  const geometry = useObjectGeometry(object.assetId, object.id)
  const surfaceRegistry = useContext(SnapSurfaces)
  useEffect(() => {
    let cancelled = false
    if (geometry) void prepareSurfaces(geometry).then(data => {
      if (!cancelled && data) surfaceRegistry?.set(object.id, data)
    })
    return () => { cancelled = true; surfaceRegistry?.delete(object.id) }
  }, [geometry, object.id, surfaceRegistry])
  const geometryError = useEditorStore((state) => state.objectGeometryErrors[object.id] ?? null)
  const meshRef = useRef<Mesh>(null!)
  const directDragRef = useRef<{ pointerId: number; offset: Vector3 } | null>(null)
  const selectedId = useEditorStore((state) => state.selectedId)
  const mode = useEditorStore((state) => state.mode)
  const select = useEditorStore((state) => state.select)
  const selectFace = useEditorStore((state) => state.selectFace)
  const commitPivotTransform = useEditorStore((state) => state.commitPivotTransform)
  const setDragging = useEditorStore((state) => state.setDragging)
  const setDragPreview = useEditorStore((state) => state.setDragPreview)
  const retryObjectGeometry = useEditorStore((state) => state.retryObjectGeometry)
  const layFlatMode = useEditorStore((state) => state.layFlatMode)
  const layFlatObject = useEditorStore((state) => state.layFlatObject)
  const centered = useMemo(() => geometry ? centeredGeometry(geometry) : null, [geometry])
  useEffect(() => () => centered?.geometry.dispose(), [centered])

  function handleMeshClick(event: { face?: { normal: { x: number; y: number; z: number } }; faceIndex?: number }) {
    if (layFlatMode && geometry?.boundingBox) {
      if (event.face) {
        const supportNormal = supportNormalFromGeometry(
          geometry,
          event.faceIndex ?? -1,
          new Vector3(event.face.normal.x, event.face.normal.y, event.face.normal.z),
        )
        layFlatObject(object.id, {
          normal: supportNormal.toArray() as Vec3,
          boundsMin: geometry.boundingBox.min.toArray() as Vec3,
          boundsMax: geometry.boundingBox.max.toArray() as Vec3,
        })
      }
      return
    }
    select(object.id)
    if (!geometry || !event.face) return
    if (!geometry.boundingBox) geometry.computeBoundingBox()
    if (!geometry.boundingBox) return
    selectFace(object.id, {
      normal: supportNormalFromGeometry(
        geometry,
        event.faceIndex ?? -1,
        new Vector3(event.face.normal.x, event.face.normal.y, event.face.normal.z),
      ).toArray() as Vec3,
      boundsMin: geometry.boundingBox.min.toArray() as Vec3,
      boundsMax: geometry.boundingBox.max.toArray() as Vec3,
    })
  }

  if (!geometry) {
    // Broken objects stay locatable and selectable (a small wireframe box at the object's own
    // transform) instead of rendering nothing — the user needs to find it to hit Retry.
    if (!geometryError) return null
    return (
      <group position={object.translationMm} quaternion={object.quaternionXyzw} scale={object.scale}>
        <mesh onClick={() => select(object.id)}>
          <boxGeometry args={[50, 50, 50]} />
          <meshBasicMaterial wireframe color="#e2685c" />
        </mesh>
        <Html position={[0, 35, 0]} center distanceFactor={10}>
          <div className="geometry-error-indicator" role="alert">
            <strong>Geometry unavailable</strong>
            <span>{geometryError}</span>
            <button
              type="button"
              aria-label="Retry geometry"
              onClick={(event) => {
                event.stopPropagation()
                retryObjectGeometry(object.id)
              }}
            >
              Retry geometry
            </button>
          </div>
        </Html>
      </group>
    )
  }

  const mesh = (
    <mesh
      ref={meshRef}
      userData={{ editorObjectId: object.id }}
      geometry={centered!.geometry}
      position={pivotPosition(object.translationMm, object.quaternionXyzw, object.scale, centered!.center)}
      quaternion={object.quaternionXyzw}
      scale={object.scale}
      onClick={handleMeshClick}
      onPointerDown={handleDirectPointerDown}
      onPointerMove={handleDirectPointerMove}
      onPointerUp={handleDirectPointerUp}
      onPointerCancel={cancelDirectDrag}
      onLostPointerCapture={cancelDirectDrag}
    >
      <meshStandardMaterial color="#9b7358" roughness={0.88} metalness={0} />
    </mesh>
  )

  if (layFlatMode) {
    if (selectedId !== object.id) return mesh
    const bounds = geometry.boundingBox!
    const center = bounds.getCenter(new Vector3())
    const size = bounds.getSize(new Vector3())
    const zones = [
      { label: 'Top', normal: [0, 1, 0] as Vec3, position: [center.x, bounds.max.y + 0.5, center.z] as Vec3, rotation: [-Math.PI / 2, 0, 0] as Vec3, dimensions: [size.x, size.z] as [number, number] },
      { label: 'Bottom', normal: [0, -1, 0] as Vec3, position: [center.x, bounds.min.y - 0.5, center.z] as Vec3, rotation: [Math.PI / 2, 0, 0] as Vec3, dimensions: [size.x, size.z] as [number, number] },
      { label: 'Right', normal: [1, 0, 0] as Vec3, position: [bounds.max.x + 0.5, center.y, center.z] as Vec3, rotation: [0, Math.PI / 2, 0] as Vec3, dimensions: [size.z, size.y] as [number, number] },
      { label: 'Left', normal: [-1, 0, 0] as Vec3, position: [bounds.min.x - 0.5, center.y, center.z] as Vec3, rotation: [0, -Math.PI / 2, 0] as Vec3, dimensions: [size.z, size.y] as [number, number] },
      { label: 'Front', normal: [0, 0, 1] as Vec3, position: [center.x, center.y, bounds.max.z + 0.5] as Vec3, rotation: [0, 0, 0] as Vec3, dimensions: [size.x, size.y] as [number, number] },
      { label: 'Back', normal: [0, 0, -1] as Vec3, position: [center.x, center.y, bounds.min.z - 0.5] as Vec3, rotation: [Math.PI, 0, 0] as Vec3, dimensions: [size.x, size.y] as [number, number] },
    ]
    const centeredZones = zones.map((zone) => ({
      ...zone,
      position: [
        zone.position[0] - centered!.center[0],
        zone.position[1] - centered!.center[1],
        zone.position[2] - centered!.center[2],
      ] as Vec3,
    }))
    return (
      <group>
        {mesh}
        <group position={pivotPosition(object.translationMm, object.quaternionXyzw, object.scale, centered!.center)} quaternion={object.quaternionXyzw} scale={object.scale}>
          {centeredZones.map((zone) => (
            <mesh key={zone.label} position={zone.position} rotation={zone.rotation} onPointerDown={(event) => event.stopPropagation()} onClick={(event) => { event.stopPropagation(); layFlatObject(object.id, { normal: zone.normal, boundsMin: bounds.min.toArray() as Vec3, boundsMax: bounds.max.toArray() as Vec3 }) }}>
              <planeGeometry args={zone.dimensions} />
              <meshBasicMaterial color="#62c6a8" transparent opacity={0.35} depthWrite={false} side={2} />
            </mesh>
          ))}
        </group>
        <Html position={[0, 45, 0]} center>
          <div className="lay-flat-candidates" role="status">Choose a highlighted support zone</div>
        </Html>
      </group>
    )
  }

  // Commit the transform ONCE, when the drag ends — not on every intermediate onObjectChange.
  // TransformControls attaches explicitly to this centered mesh and mutates it directly and drei re-renders the
  // Canvas frame on its own 'change' event, so the drag stays visually smooth without this.
  // Committing to the store on every intermediate change re-renders EditorObjectMesh, which
  // gives `mesh` a new element identity; drei's TransformControls re-runs its attach effect
  // whenever `children` changes identity, calling detach() (which clears its drag axis) in the
  // middle of the drag. That silently stops the drag from moving further and, since axis also
  // gates the mouseUp dispatch, leaves orbit controls disabled after the user releases the mouse.
  function handleObjectChange() {
    const target = meshRef.current
    if (!target) return
    setDragPreview({
      translationMm: target.position.toArray() as Vec3,
      quaternionXyzw: target.quaternion.toArray() as Vec4,
    })
    const surfaces = surfaceRegistry?.get(object.id)
    const targets: SnapObject[] = useEditorStore.getState().objects.flatMap(candidate => {
      const data = surfaceRegistry?.get(candidate.id)
      return data && candidate.id !== object.id ? [{ id: candidate.id, surfaces: data, translation: candidate.translationMm,
        quaternion: candidate.quaternionXyzw, scale: candidate.scale }] : []
    })
    commitPivotTransform(object.id, target.position.toArray() as Vec3,
      target.quaternion.toArray() as Vec4, centered!.center, mode, surfaces ? { surfaces, targets } : undefined)
  }

  function handleDirectPointerDown(event: { stopPropagation: () => void; preventDefault?: () => void; pointerId: number; ray: { origin: Vector3; direction: Vector3 }; target: { setPointerCapture?: (id: number) => void } }) {
    if (mode !== 'translate' || layFlatMode || !meshRef.current) return
    event.stopPropagation()
    event.preventDefault?.()
    select(object.id)
    const hit = intersectHorizontalDragPlane(event.ray.origin, event.ray.direction, meshRef.current.position.y)
    if (!hit) return
    directDragRef.current = { pointerId: event.pointerId, offset: hit.clone().sub(meshRef.current.position) }
    event.target.setPointerCapture?.(event.pointerId)
    setDragging(true)
  }

  function handleDirectPointerMove(event: { stopPropagation?: () => void; pointerId: number; ray: { origin: Vector3; direction: Vector3 } }) {
    const drag = directDragRef.current
    const target = meshRef.current
    if (!drag || drag.pointerId !== event.pointerId || !target) return
    event.stopPropagation?.()
    const hit = intersectHorizontalDragPlane(event.ray.origin, event.ray.direction, target.position.y)
    if (!hit) return
    target.position.copy(hit.sub(drag.offset))
    handleLivePreview()
  }

  function handleDirectPointerUp(event: { pointerId: number }) {
    if (!directDragRef.current || directDragRef.current.pointerId !== event.pointerId) return
    directDragRef.current = null
    handleObjectChange()
    setDragPreview(null)
    setDragging(false)
  }

  function cancelDirectDrag(event: { pointerId: number }) {
    if (!directDragRef.current || directDragRef.current.pointerId !== event.pointerId) return
    directDragRef.current = null
    setDragPreview(null)
    setDragging(false)
  }

  function handleLivePreview() {
    const target = meshRef.current
    if (!target) return
    setDragPreview({ translationMm: target.position.toArray() as Vec3, quaternionXyzw: target.quaternion.toArray() as Vec4 })
  }

  return (
    <TransformControls
      object={meshRef}
      mode={mode}
      enabled={selectedId === object.id}
      visible={selectedId === object.id}
      axis={mode === 'translate' ? 'XZ' : undefined}
      onMouseDown={() => setDragging(true)}
      onObjectChange={handleLivePreview}
      onMouseUp={() => {
        handleObjectChange()
        setDragging(false)
        setDragPreview(null)
      }}
    >
      {mesh}
    </TransformControls>
  )
})

// Split out of EditorCanvas because useThree() only resolves the R3F context from inside
// <Canvas>'s own subtree, not from the component that renders <Canvas> itself.
function SceneContent() {
  const surfaceRegistry = useMemo(() => new Map<number, SurfaceData>(), [])
  const objects = useEditorStore((state) => state.objects)
  const selectedId = useEditorStore((state) => state.selectedId)
  const orbitEnabled = useEditorStore((state) => state.orbitEnabled)
  const fitRequestTick = useEditorStore((state) => state.fitRequestTick)
  const { camera } = useThree()
  const groupRef = useRef<Group>(null)
  const controlsRef = useRef<OrbitControlsImpl | null>(null)
  // Fires once on mount purely to flip this past its initial value — the fit effect below must
  // never auto-fit on load, only on a later requestFitToScene() call (an explicit user action).
  const mountedRef = useRef(false)

  useEffect(() => {
    if (!mountedRef.current) {
      mountedRef.current = true
      return
    }
    const group = groupRef.current
    // Guards against a ref that isn't a real THREE.Object3D (e.g. this component's own test
    // harness, which mocks <Canvas>/<group> as plain DOM nodes with no scene graph behind them) —
    // Box3.setFromObject requires a genuine Object3D to traverse.
    let box = group instanceof Object3DClass ? visibleMeshBounds(group, selectedId) : null
    // If the selected object has no renderable mesh, retain a useful all-scene frame rather than
    // silently falling back to the origin/default view.
    if (selectedId !== null && !box && group instanceof Object3DClass) box = visibleMeshBounds(group)
    const perspectiveCamera = camera as PerspectiveCamera
    const previousPosition = perspectiveCamera.position.clone()
    const previousQuaternion = perspectiveCamera.quaternion.clone()
    const previousNear = perspectiveCamera.near
    const previousFar = perspectiveCamera.far
    const previousTarget = controlsRef.current?.target.clone()
    const { position, target } = frameBox(box, perspectiveCamera)
    const distance = position.distanceTo(target)
    if (!position.toArray().every(Number.isFinite) || !target.toArray().every(Number.isFinite) || !Number.isFinite(distance) || distance <= 0) {
      perspectiveCamera.position.copy(previousPosition)
      perspectiveCamera.quaternion.copy(previousQuaternion)
      perspectiveCamera.near = previousNear
      perspectiveCamera.far = previousFar
      if (previousTarget && controlsRef.current) controlsRef.current.target.copy(previousTarget)
      return
    }
    perspectiveCamera.position.copy(position)
    perspectiveCamera.near = Math.max(0.1, distance / 100)
    perspectiveCamera.far = Math.max(10000, distance * 4)
    perspectiveCamera.lookAt(target)
    perspectiveCamera.updateProjectionMatrix()
    controlsRef.current?.target.copy(target)
    controlsRef.current?.update()
    // `fitRequestTick` is the only real trigger here — `camera` is the same R3F-managed instance
    // for the whole life of this <Canvas>, so it never needs to force a re-run on its own.
  }, [fitRequestTick])

  return (
    <>
      <color attach="background" args={['#0f161b']} />
      <fog attach="fog" args={['#0f161b', 800, 3000]} />
      <hemisphereLight args={['#8fa9b8', '#1c2830', 0.5]} />
      <ambientLight intensity={0.35} />
      <directionalLight position={[350, 500, 250]} intensity={1.5} />
      <directionalLight position={[-300, 220, -200]} intensity={0.45} />
      <gridHelper args={[2000, 20, '#3a4b56', '#22303a']} />
      <gridHelper args={[200, 20, '#4d616d', '#2a3944']} />
      <SnapSurfaces.Provider value={surfaceRegistry}>
      <group ref={groupRef}>
        {objects.map((object) => (
          <group key={object.id} userData={{ editorObjectId: object.id }}>
            <EditorObjectMesh object={object} />
          </group>
        ))}
      </group>
      </SnapSurfaces.Provider>
      <OrbitControls ref={controlsRef} enabled={orbitEnabled} />
    </>
  )
}

export function EditorCanvas() {
  return (
    <Canvas camera={{ position: [0, 500, 500] }}>
      <SceneContent />
    </Canvas>
  )
}
