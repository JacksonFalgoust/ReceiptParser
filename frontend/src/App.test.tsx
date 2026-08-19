import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import App from './App'

describe('App routing', () => {
  it('renders the upload screen at the root route', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: /upload receipt/i })).toBeInTheDocument()
  })

  it('renders the bill room screen for a room code route', () => {
    render(
      <MemoryRouter initialEntries={['/bill/ABC123']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: /bill room/i })).toBeInTheDocument()
  })
})
