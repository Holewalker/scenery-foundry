import { useEffect, useState } from 'react'
import { eulerDegreesFromQuaternion, quaternionFromEulerDegrees } from './transform'
import type { Vec3 } from './store'
import { useEditorStore } from './store'

type Axis = 'X' | 'Y' | 'Z'
type Field = `position${Axis}` | `rotation${Axis}`

const positionFields: Array<{ axis: Axis; field: Field }> = [
  { axis: 'X', field: 'positionX' },
  { axis: 'Y', field: 'positionY' },
  { axis: 'Z', field: 'positionZ' },
]
const rotationFields: Array<{ axis: Axis; field: Field }> = [
  { axis: 'X', field: 'rotationX' },
  { axis: 'Y', field: 'rotationY' },
  { axis: 'Z', field: 'rotationZ' },
]

function finiteValue(value: string): number | null {
  if (value.trim() === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}

export function TransformPanel() {
  const selectedId = useEditorStore((state) => state.selectedId)
  const object = useEditorStore((state) => state.objects.find((candidate) => candidate.id === selectedId) ?? null)
  const setTranslation = useEditorStore((state) => state.setTranslation)
  const setRotation = useEditorStore((state) => state.setRotation)
  const [values, setValues] = useState<Record<Field, string>>({ positionX: '', positionY: '', positionZ: '', rotationX: '', rotationY: '', rotationZ: '' })

  useEffect(() => {
    if (!object) {
      setValues({ positionX: '', positionY: '', positionZ: '', rotationX: '', rotationY: '', rotationZ: '' })
      return
    }
    const degrees = eulerDegreesFromQuaternion(object.quaternionXyzw)
    setValues({
      positionX: String(object.translationMm[0]), positionY: String(object.translationMm[1]), positionZ: String(object.translationMm[2]),
      rotationX: String(Number(degrees[0].toFixed(4))), rotationY: String(Number(degrees[1].toFixed(4))), rotationZ: String(Number(degrees[2].toFixed(4))),
    })
  }, [selectedId, object?.translationMm.join(','), object?.quaternionXyzw.join(',')])

  function change(field: Field, value: string) {
    setValues((current) => ({ ...current, [field]: value }))
    if (!object) return
    const numeric = finiteValue(value)
    if (numeric === null) return
    if (field.startsWith('position')) {
      const index = field.endsWith('X') ? 0 : field.endsWith('Y') ? 1 : 2
      const next = [...object.translationMm] as Vec3
      next[index] = numeric
      setTranslation(object.id, next)
    } else {
      const index = field.endsWith('X') ? 0 : field.endsWith('Y') ? 1 : 2
      const next = eulerDegreesFromQuaternion(object.quaternionXyzw)
      next[index] = numeric
      setRotation(object.id, quaternionFromEulerDegrees(next))
    }
  }

  function resetField(field: Field) {
    if (finiteValue(values[field]) !== null || !object) return
    const degrees = eulerDegreesFromQuaternion(object.quaternionXyzw)
    const fallback = field.startsWith('position')
      ? object.translationMm[field.endsWith('X') ? 0 : field.endsWith('Y') ? 1 : 2]
      : degrees[field.endsWith('X') ? 0 : field.endsWith('Y') ? 1 : 2]
    setValues((current) => ({ ...current, [field]: String(Number(fallback.toFixed(4))) }))
  }

  if (!object) return <section className="transform-panel"><h2>Measurements</h2><p>Select an object to edit its measurements.</p></section>

  return (
    <section className="transform-panel" aria-label="Object measurements">
      <h2>Measurements</h2>
      <fieldset><legend>Position (mm)</legend>{positionFields.map(({ axis, field }) => <label key={field}>{axis}<input aria-label={`Position ${axis} (mm)`} type="number" step="any" value={values[field]} onChange={(event) => change(field, event.target.value)} onBlur={() => resetField(field)} /></label>)}</fieldset>
      <fieldset><legend>Rotation (degrees)</legend>{rotationFields.map(({ axis, field }) => <label key={field}>{axis}<input aria-label={`Rotation ${axis} (degrees)`} type="number" step="any" value={values[field]} onChange={(event) => change(field, event.target.value)} onBlur={() => resetField(field)} /></label>)}</fieldset>
    </section>
  )
}
