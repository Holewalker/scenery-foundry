import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { App } from './App'

describe('App', () => {
  it('renders the product shell', () => {
    render(<App />)
    expect(screen.getByRole('heading', { name: 'Scenery Foundry' })).toBeInTheDocument()
    expect(screen.getByText('3D workspace bootstrap')).toBeInTheDocument()
  })
})
