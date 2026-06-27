import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Button } from '../button'

describe('Button', () => {
    it('renders children text', () => {
        render(<Button>Click me</Button>)
        expect(screen.getByRole('button')).toHaveTextContent('Click me')
    })

    it('renders with default variant', () => {
        render(<Button>Default</Button>)
        const button = screen.getByRole('button')
        expect(button).toHaveAttribute('data-slot', 'button')
        expect(button).toHaveClass('bg-primary')
    })

    it('renders with outline variant', () => {
        render(<Button variant="outline">Outline</Button>)
        expect(screen.getByRole('button')).toHaveClass('border-border')
    })

    it('renders with destructive variant', () => {
        render(<Button variant="destructive">Destructive</Button>)
        expect(screen.getByRole('button')).toHaveClass('bg-destructive/10')
    })

    it('renders with size="sm"', () => {
        render(<Button size="sm">Small</Button>)
        expect(screen.getByRole('button')).toHaveClass('h-7')
    })

    it('renders disabled state', () => {
        render(<Button disabled>Disabled</Button>)
        expect(screen.getByRole('button')).toBeDisabled()
    })

    it('fires onClick when clicked', async () => {
        const handleClick = vi.fn()
        const user = userEvent.setup()
        render(<Button onClick={handleClick}>Click</Button>)
        await user.click(screen.getByRole('button'))
        expect(handleClick).toHaveBeenCalledTimes(1)
    })

    it('does not fire onClick when disabled', async () => {
        const handleClick = vi.fn()
        const user = userEvent.setup()
        render(<Button disabled onClick={handleClick}>Click</Button>)
        await user.click(screen.getByRole('button'))
        expect(handleClick).not.toHaveBeenCalled()
    })

    it('forwards className prop', () => {
        render(<Button className="custom-class">Styled</Button>)
        expect(screen.getByRole('button')).toHaveClass('custom-class')
    })
})
