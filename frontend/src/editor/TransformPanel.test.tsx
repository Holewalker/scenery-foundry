import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { resetEditorStore, useEditorStore } from './store'
import { TransformPanel } from './TransformPanel'

beforeEach(() => resetEditorStore())

describe('TransformPanel', () => {
  it('shows a no-selection state and measured fields when an object is selected', () => {
    const { rerender } = render(<TransformPanel />)
    expect(screen.getByText('Select an object to edit its measurements.')).toBeInTheDocument()

    const id = useEditorStore.getState().insert('asset-1')
    useEditorStore.getState().move(id, [12, 24, 36])
    rerender(<TransformPanel />)
    expect(screen.getByLabelText('Position X (mm)')).toHaveValue(12)
    expect(screen.getByLabelText('Position Y (mm)')).toHaveValue(24)
    expect(screen.getByLabelText('Rotation Z (degrees)')).toHaveValue(0)
  })

  it('updates position immutably from numeric fields', () => {
    const id = useEditorStore.getState().insert('asset-1')
    render(<TransformPanel />)
    const objectBefore = useEditorStore.getState().objects[0]
    fireEvent.change(screen.getByLabelText('Position X (mm)'), { target: { value: '18.5' } })
    expect(useEditorStore.getState().objects[0]?.translationMm).toEqual([18.5, 0, 0])
    expect(useEditorStore.getState().objects[0]).not.toBe(objectBefore)
    expect(useEditorStore.getState().selectedId).toBe(id)
  })

  it('updates rotation in degrees and ignores non-finite values', () => {
    useEditorStore.getState().insert('asset-1')
    render(<TransformPanel />)
    fireEvent.change(screen.getByLabelText('Rotation Y (degrees)'), { target: { value: '45' } })
    expect(screen.getByLabelText('Rotation Y (degrees)')).toHaveValue(45)
    const quaternion = useEditorStore.getState().objects[0]?.quaternionXyzw
    expect(quaternion?.[1]).toBeCloseTo(Math.sin(Math.PI / 8), 8)
    fireEvent.change(screen.getByLabelText('Position Z (mm)'), { target: { value: 'not-a-number' } })
    expect(useEditorStore.getState().objects[0]?.translationMm).toEqual([0, 0, 0])
  })
})
