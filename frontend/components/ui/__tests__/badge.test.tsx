import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {Badge} from '../badge'

describe('Badge', () => {
    it('renders children text', () => {
        render(<Badge>Active</Badge>)
        expect(screen.getByText('Active')).toBeInTheDocument()
    })

    it('renders with default variant class', () => {
        render(<Badge>Default</Badge>)
        expect(screen.getByText('Default')).toHaveClass('bg-primary')
    })

    it('renders with success variant', () => {
        render(<Badge variant="success">Success</Badge>)
        expect(screen.getByText('Success')).toHaveClass('bg-healthy/10')
    })

    it('renders with warning variant', () => {
        render(<Badge variant="warning">Warning</Badge>)
        expect(screen.getByText('Warning')).toHaveClass('bg-warning/10')
    })

    it('renders with destructive variant', () => {
        render(<Badge variant="destructive">Error</Badge>)
        expect(screen.getByText('Error')).toHaveClass('bg-destructive/10')
    })

    it('renders with outline variant', () => {
        render(<Badge variant="outline">Outline</Badge>)
        expect(screen.getByText('Outline')).toHaveClass('border-border')
    })

    it('forwards className prop', () => {
        render(<Badge className="custom-class">Custom</Badge>)
        expect(screen.getByText('Custom')).toHaveClass('custom-class')
    })

    it('renders as a span by default', () => {
        render(<Badge>Tag</Badge>)
        const el = screen.getByText('Tag')
        expect(el.tagName).toBe('SPAN')
    })
})