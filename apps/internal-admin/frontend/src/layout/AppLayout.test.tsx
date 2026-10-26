import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AppLayout } from './AppLayout'
import { App } from '../App'

describe('AppLayout', () => {
  it('renders the console title and the main landmark with its content', () => {
    render(<AppLayout><p>본문</p></AppLayout>)
    expect(screen.getByRole('banner')).toHaveTextContent('PayCore 관리자')
    expect(screen.getByRole('main')).toHaveTextContent('본문')
  })

  it('App renders inside the layout', () => {
    render(<App />)
    expect(screen.getByRole('banner')).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
  })
})
