import { Canvas } from '@react-three/fiber'
import { OrbitControls, TransformControls } from '@react-three/drei'
import { useEffect, useRef, useState } from 'react'
import type { BufferGeometry, Mesh } from 'three'
import { STLLoader } from 'three/examples/jsm/loaders/STLLoader.js'
import { fetchAssetStl } from '../api/client'
import type { EditorObject, Vec3, Vec4 } from './store'
import { useEditorStore } from './store'

interface EditorCanvasProps {
  projectId: string
}

function useObjectGeometry(projectId: string, assetId: string): BufferGeometry | null {
  const [geometry, setGeometry] = useState<BufferGeometry | null>(null)
  useEffect(() => {
    let cancelled = false
    fetchAssetStl(projectId, assetId).then((buffer) => {
      if (!cancelled) setGeometry(new STLLoader().parse(buffer))
    })
    return () => {
      cancelled = true
    }
  }, [projectId, assetId])
  return geometry
}

function EditorObjectMesh({ projectId, object }: { projectId: string; object: EditorObject }) {
  const geometry = useObjectGeometry(projectId, object.assetId)
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
      onMouseUp={() => setDragging(false)}
      onObjectChange={handleObjectChange}
    >
      {mesh}
    </TransformControls>
  )
}

export function EditorCanvas({ projectId }: EditorCanvasProps) {
  const objects = useEditorStore((state) => state.objects)
  const orbitEnabled = useEditorStore((state) => state.orbitEnabled)

  return (
    <Canvas camera={{ position: [0, 500, 500] }}>
      <ambientLight intensity={0.6} />
      <directionalLight position={[500, 500, 500]} />
      {objects.map((object) => (
        <EditorObjectMesh key={object.id} projectId={projectId} object={object} />
      ))}
      <OrbitControls enabled={orbitEnabled} />
    </Canvas>
  )
}
