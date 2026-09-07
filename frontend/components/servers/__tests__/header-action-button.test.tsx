import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {HeaderActionButton} from '../header-action-button'

describe('HeaderActionButton', () => {
    it('renders label and icon', () => {
        render(<HeaderActionButton icon={<span>I</span>} label="Start" onClick={vi.fn()} variant="green"/>)
        expect(screen.getByText('Start')).toBeInTheDocument()
    })

    it('fires onClick when clicked', async () => {
        const onClick = vi.fn()
        const user = userEvent.setup()
        render(<HeaderActionButton icon={<span>I</span>} label="Start" onClick={onClick} variant="green"/>)
        await user.click(screen.getByRole('button'))
        expect(onClick).toHaveBeenCalled()
    })

    it('is disabled when disabled prop set', () => {
        render(<HeaderActionButton icon={<span>I</span>} label="Start" onClick={vi.fn()} variant="green" disabled/>)
        expect(screen.getByRole('button')).toBeDisabled()
    })

    it('shows spinner when loading', () => {
        render(<HeaderActionButton icon={<span>I</span>} label="Start" onClick={vi.fn()} variant="green" loading/>)
        const btn = screen.getByRole('button')
        expect(btn).toBeDisabled()
        expect(btn.querySelector('.animate-spin')).toBeInTheDocument()
    })

    it('applies green variant classes', () => {
        render(<HeaderActionButton icon={<span>I</span>} label="Start" onClick={vi.fn()} variant="green"/>)
        expect(screen.getByRole('button').className).toContain('text-healthy')
    })

    it('applies red variant classes', () => {
        render(<HeaderActionButton icon={<span>I</span>} label="Stop" onClick={vi.fn()} variant="red"/>)
        expect(screen.getByRole('button').className).toContain('text-error')
    })

    it('applies amber variant classes', () => {
        render(<HeaderActionButton icon={<span>I</span>} label="Restart" onClick={vi.fn()} variant="amber"/>)
        expect(screen.getByRole('button').className).toContain('text-warning')
    })
})