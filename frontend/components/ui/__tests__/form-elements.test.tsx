import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Modal, Field, BTN_PRIMARY, BTN_GHOST, TextField, SelectField, TextAreaField } from '../form-elements'

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
    it('BTN_PRIMARY is a string', () => {
        expect(typeof BTN_PRIMARY).toBe('string')
    })
    it('BTN_GHOST is a string', () => {
        expect(typeof BTN_GHOST).toBe('string')
    })
})

describe('TextField', () => {
    it('renders an input', () => {
        render(<TextField placeholder="name" onChange={vi.fn()} value="" />)
        expect(screen.getByPlaceholderText('name')).toBeInTheDocument()
    })

    it('defaults to md size and surface-high surface', () => {
        render(<TextField placeholder="name" onChange={vi.fn()} value="" />)
        const el = screen.getByPlaceholderText('name')
        expect(el.className).toContain('text-[13px]')
        expect(el.className).toContain('px-3')
        expect(el.className).toContain('py-2')
        expect(el.className).toContain('bg-surface-high')
    })

    it('applies sm size classes', () => {
        render(<TextField placeholder="name" onChange={vi.fn()} value="" fieldSize="sm" />)
        const el = screen.getByPlaceholderText('name')
        expect(el.className).toContain('text-[12px]')
        expect(el.className).toContain('px-2.5')
        expect(el.className).toContain('py-1.5')
    })

    it.each([
        ['bg', 'bg-bg'],
        ['surface', 'bg-surface'],
        ['surface-high', 'bg-surface-high'],
        ['surface-higher', 'bg-surface-higher'],
    ] as const)('applies surface=%s -> %s', (surface, expectedClass) => {
        render(<TextField placeholder="name" onChange={vi.fn()} value="" surface={surface} />)
        const el = screen.getByPlaceholderText('name')
        expect(el.className).toContain(expectedClass)
    })

    it('merges a passed className', () => {
        render(<TextField placeholder="name" onChange={vi.fn()} value="" className="w-32" />)
        expect(screen.getByPlaceholderText('name').className).toContain('w-32')
    })

    it('supports disabled state', () => {
        render(<TextField placeholder="name" onChange={vi.fn()} value="" disabled />)
        expect(screen.getByPlaceholderText('name')).toBeDisabled()
    })
})

describe('SelectField', () => {
    it('renders a select with options', () => {
        render(
            <SelectField value="a" onChange={vi.fn()} aria-label="choice">
                <option value="a">A</option>
                <option value="b">B</option>
            </SelectField>
        )
        expect(screen.getByRole('combobox')).toBeInTheDocument()
        expect(screen.getByText('A')).toBeInTheDocument()
    })

    it('applies size and surface classes', () => {
        render(
            <SelectField value="a" onChange={vi.fn()} aria-label="choice" fieldSize="sm" surface="surface-higher">
                <option value="a">A</option>
            </SelectField>
        )
        const el = screen.getByRole('combobox')
        expect(el.className).toContain('text-[12px]')
        expect(el.className).toContain('bg-surface-higher')
    })

    it('supports disabled state', () => {
        render(
            <SelectField value="a" onChange={vi.fn()} aria-label="choice" disabled>
                <option value="a">A</option>
            </SelectField>
        )
        expect(screen.getByRole('combobox')).toBeDisabled()
    })
})

describe('TextAreaField', () => {
    it('renders a textarea', () => {
        render(<TextAreaField placeholder="desc" onChange={vi.fn()} value="" />)
        expect(screen.getByPlaceholderText('desc')).toBeInTheDocument()
    })

    it('defaults rows based on size', () => {
        render(<TextAreaField placeholder="desc-md" onChange={vi.fn()} value="" />)
        expect(screen.getByPlaceholderText('desc-md')).toHaveAttribute('rows', '3')
    })

    it('defaults rows to 2 for sm size', () => {
        render(<TextAreaField placeholder="desc-sm" onChange={vi.fn()} value="" fieldSize="sm" />)
        expect(screen.getByPlaceholderText('desc-sm')).toHaveAttribute('rows', '2')
    })

    it('allows overriding rows', () => {
        render(<TextAreaField placeholder="desc-rows" onChange={vi.fn()} value="" rows={5} />)
        expect(screen.getByPlaceholderText('desc-rows')).toHaveAttribute('rows', '5')
    })

    it('applies resize-none class', () => {
        render(<TextAreaField placeholder="desc-resize" onChange={vi.fn()} value="" />)
        expect(screen.getByPlaceholderText('desc-resize').className).toContain('resize-none')
    })
})
