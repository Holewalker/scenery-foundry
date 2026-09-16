import { Canvas, useThree } from '@react-three/fiber'
import { Html, OrbitControls, TransformControls } from '@react-three/drei'
import { memo, useEffect, useRef, useState } from 'react'
import type { BufferGeometry, Group, Mesh, Object3D, PerspectiveCamera } from 'three'
import { Box3, Object3D as Object3DClass } from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import type { OrbitControls as OrbitControlsImpl } from 'three-stdlib'
import { fetchAssetPreview } from '../api/client'
import { frameBox } from './cameraFit'
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

function visibleMeshBounds(root: Object3D): Box3 | null {
  const bounds = new Box3()
  let found = false
  root.traverse((child) => {
    const mesh = child as Mesh
    if (!mesh.isMesh || !mesh.visible || !mesh.geometry) return
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
  const geometryError = useEditorStore((state) => state.objectGeometryErrors[object.id] ?? null)
  const meshRef = useRef<Mesh>(null)
  const selectedId = useEditorStore((state) => state.selectedId)
  const mode = useEditorStore((state) => state.mode)
  const select = useEditorStore((state) => state.select)
  const move = useEditorStore((state) => state.move)
  const rotate = useEditorStore((state) => state.rotate)
  const setDragging = useEditorStore((state) => state.setDragging)
  const retryObjectGeometry = useEditorStore((state) => state.retryObjectGeometry)

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
      geometry={geometry}
      position={object.translationMm}
      quaternion={object.quaternionXyzw}
      scale={object.scale}
      onClick={() => select(object.id)}
    >
      <meshStandardMaterial color="#9b7358" roughness={0.88} metalness={0} />
    </mesh>
  )

  if (selectedId !== object.id) return mesh

  // Commit the transform ONCE, when the drag ends — not on every intermediate onObjectChange.
  // TransformControls already mutates the attached mesh directly and drei re-renders the
  // Canvas frame on its own 'change' event, so the drag stays visually smooth without this.
  // Committing to the store on every intermediate change re-renders EditorObjectMesh, which
  // gives `mesh` a new element identity; drei's TransformControls re-runs its attach effect
  // whenever `children` changes identity, calling detach() (which clears its drag axis) in the
  // middle of the drag. That silently stops the drag from moving further and, since axis also
  // gates the mouseUp dispatch, leaves orbit controls disabled after the user releases the mouse.
  function handleObjectChange() {
    const target = meshRef.current
    if (!target) return
    if (mode === 'translate') move(object.id, target.position.toArray() as Vec3)
    else rotate(object.id, target.quaternion.toArray() as Vec4)
  }

  return (
    <TransformControls
      mode={mode}
      onMouseDown={() => setDragging(true)}
      onMouseUp={() => {
        handleObjectChange()
        setDragging(false)
      }}
    >
      {mesh}
    </TransformControls>
  )
})

// Split out of EditorCanvas because useThree() only resolves the R3F context from inside
// <Canvas>'s own subtree, not from the component that renders <Canvas> itself.
function SceneContent() {
  const objects = useEditorStore((state) => state.objects)
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
    const box = group instanceof Object3DClass ? visibleMeshBounds(group) : null
    const perspectiveCamera = camera as PerspectiveCamera
    const previousPosition = perspectiveCamera.position.clone()
    const previousNear = perspectiveCamera.near
    const previousFar = perspectiveCamera.far
    const previousTarget = controlsRef.current?.target.clone()
    const { position, target } = frameBox(box, perspectiveCamera)
    const distance = position.distanceTo(target)
    if (!position.toArray().every(Number.isFinite) || !target.toArray().every(Number.isFinite) || !Number.isFinite(distance) || distance <= 0) {
      perspectiveCamera.position.copy(previousPosition)
      perspectiveCamera.near = previousNear
      perspectiveCamera.far = previousFar
      if (previousTarget && controlsRef.current) controlsRef.current.target.copy(previousTarget)
      return
    }
    perspectiveCamera.position.copy(position)
    perspectiveCamera.near = Math.max(0.1, distance / 100)
    perspectiveCamera.far = Math.max(10000, distance * 4)
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
      <group ref={groupRef}>
        {objects.map((object) => (
          <EditorObjectMesh key={object.id} object={object} />
        ))}
      </group>
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
