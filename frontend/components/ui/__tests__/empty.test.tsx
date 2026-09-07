import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {Empty, EmptyHeader, EmptyTitle, EmptyDescription, EmptyContent, EmptyMedia} from '../empty'

describe('Empty', () => {
    it('renders children', () => {
        render(<Empty data-testid="empty">Nothing here</Empty>)
        expect(screen.getByTestId('empty')).toHaveTextContent('Nothing here')
    })

    it('has dashed border class', () => {
        render(<Empty data-testid="empty"/>)
        expect(screen.getByTestId('empty')).toHaveClass('border-dashed')
    })

    it('forwards className', () => {
        render(<Empty data-testid="empty" className="custom-empty"/>)
        expect(screen.getByTestId('empty')).toHaveClass('custom-empty')
    })
})

describe('EmptyHeader', () => {
    it('renders children', () => {
        render(<EmptyHeader data-testid="header"><EmptyTitle>No items</EmptyTitle></EmptyHeader>)
        expect(screen.getByTestId('header')).toContainElement(screen.getByText('No items'))
    })
})

describe('EmptyTitle', () => {
    it('renders title text', () => {
        render(<EmptyTitle>No results</EmptyTitle>)
        expect(screen.getByText('No results')).toBeInTheDocument()
    })
})

describe('EmptyDescription', () => {
    it('renders description text', () => {
        render(<EmptyDescription>Try a different filter</EmptyDescription>)
        expect(screen.getByText('Try a different filter')).toBeInTheDocument()
    })
})

describe('EmptyContent', () => {
    it('renders children', () => {
        render(<EmptyContent data-testid="content">Actions here</EmptyContent>)
        expect(screen.getByTestId('content')).toHaveTextContent('Actions here')
    })
})

describe('EmptyMedia', () => {
    it('renders children', () => {
        render(<EmptyMedia data-testid="media">icon</EmptyMedia>)
        expect(screen.getByTestId('media')).toBeInTheDocument()
    })

    it('applies icon variant classes', () => {
        render(<EmptyMedia data-testid="media" variant="icon"/>)
        const el = screen.getByTestId('media')
        expect(el.className).toContain('rounded-lg')
    })
})