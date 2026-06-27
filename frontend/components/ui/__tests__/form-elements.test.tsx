import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Modal, Field, INPUT, BTN_PRIMARY, BTN_GHOST } from '../form-elements'

describe('Modal', () => {
    it('renders title, children, and close button', () => {
        render(<Modal title="Settings" onClose={vi.fn()}>content here</Modal>)
        expect(screen.getByText('Settings')).toBeInTheDocument()
        expect(screen.getByText('content here')).toBeInTheDocument()
        expect(screen.getByRole('button')).toBeInTheDocument()
    })

    it('fires onClose when close button clicked', () => {
        const onClose = vi.fn()
        render(<Modal title="Settings" onClose={onClose}>content</Modal>)
        fireEvent.click(screen.getByRole('button'))
        expect(onClose).toHaveBeenCalled()
    })
})

describe('Field', () => {
    it('renders label and children wrapper', () => {
        render(<Field label="Server Name"><span>child</span></Field>)
        expect(screen.getByText('Server Name')).toBeInTheDocument()
        expect(screen.getByText('child')).toBeInTheDocument()
    })
})

describe('String constants', () => {
    it('INPUT is a string', () => {
        expect(typeof INPUT).toBe('string')
    })
    it('BTN_PRIMARY is a string', () => {
        expect(typeof BTN_PRIMARY).toBe('string')
    })
    it('BTN_GHOST is a string', () => {
        expect(typeof BTN_GHOST).toBe('string')
    })
})
