import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Switch } from '../switch'

describe('Switch', () => {
    it('renders with default size', () => {
        render(<Switch/>)
        expect(screen.getByRole('switch')).toHaveAttribute('data-size', 'default')
    })

    it('renders with size="sm"', () => {
        render(<Switch size="sm"/>)
        expect(screen.getByRole('switch')).toHaveAttribute('data-size', 'sm')
    })

    it('forwards className', () => {
        render(<Switch className="test-class"/>)
        expect(screen.getByRole('switch')).toHaveClass('test-class')
    })

    it('passes through checked prop to Root', () => {
        render(<Switch checked/>)
        expect(screen.getByRole('switch')).toHaveAttribute('data-checked')
    })

    it('fires onCheckedChange when clicked', () => {
        const handleChange = vi.fn()
        render(<Switch onCheckedChange={handleChange}/>)
        fireEvent.click(screen.getByRole('switch'))
        expect(handleChange).toHaveBeenCalled()
    })
})
