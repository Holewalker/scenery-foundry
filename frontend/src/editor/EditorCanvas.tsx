import { Canvas } from '@react-three/fiber'
import { OrbitControls, TransformControls } from '@react-three/drei'
import { memo, useEffect, useRef, useState } from 'react'
import type { BufferGeometry, Mesh, Object3D } from 'three'
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js'
import { fetchAssetPreview } from '../api/client'
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

function useObjectGeometry(assetId: string): BufferGeometry | null {
  const [geometry, setGeometry] = useState<BufferGeometry | null>(null)
  const setError = useEditorStore((state) => state.setError)
  useEffect(() => {
    let cancelled = false
    fetchAssetPreview(assetId)
      .then((buffer) => new GLTFLoader().parseAsync(buffer, ''))
      .then((gltf) => {
        if (cancelled) return
        const meshGeometry = firstMeshGeometry(gltf.scene)
        if (!meshGeometry) throw new Error('preview.glb scene graph contains no mesh')
        setGeometry(meshGeometry)
      })
      .catch(() => {
        if (!cancelled) setError('Failed to load object geometry.')
      })
    return () => {
      cancelled = true
    }
  }, [assetId, setError])
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
  const geometry = useObjectGeometry(object.assetId)
  const meshRef = useRef<Mesh>(null)
  const selectedId = useEditorStore((state) => state.selectedId)
  const mode = useEditorStore((state) => state.mode)
  const select = useEditorStore((state) => state.select)
  const move = useEditorStore((state) => state.move)
  const rotate = useEditorStore((state) => state.rotate)
  const setDragging = useEditorStore((state) => state.setDragging)

  if (!geometry) return null

  const mesh = (
    <mesh
      ref={meshRef}
      geometry={geometry}
      position={object.translationMm}
      quaternion={object.quaternionXyzw}
      scale={object.scale}
      onClick={() => select(object.id)}
    />
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

export function EditorCanvas() {
  const objects = useEditorStore((state) => state.objects)
  const orbitEnabled = useEditorStore((state) => state.orbitEnabled)

  return (
    <Canvas camera={{ position: [0, 500, 500] }}>
      <color attach="background" args={['#0f161b']} />
      <fog attach="fog" args={['#0f161b', 800, 3000]} />
      <hemisphereLight args={['#8fa9b8', '#1c2830', 0.5]} />
      <ambientLight intensity={0.6} />
      <directionalLight position={[500, 500, 500]} />
      <gridHelper args={[2000, 20, '#3a4b56', '#22303a']} />
      <gridHelper args={[200, 20, '#4d616d', '#2a3944']} />
      {objects.map((object) => (
        <EditorObjectMesh key={object.id} object={object} />
      ))}
      <OrbitControls enabled={orbitEnabled} />
    </Canvas>
  )
}
