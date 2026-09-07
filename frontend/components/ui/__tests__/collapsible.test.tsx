import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {Collapsible, CollapsibleTrigger, CollapsibleContent} from '../collapsible'

describe('Collapsible', () => {
    it('renders trigger and content', () => {
        render(
            <Collapsible defaultOpen={true}>
                <CollapsibleTrigger>Toggle</CollapsibleTrigger>
                <CollapsibleContent>Content</CollapsibleContent>
            </Collapsible>,
        )
        expect(screen.getByText('Toggle')).toBeInTheDocument()
        expect(screen.getByText('Content')).toBeInTheDocument()
    })

    it('sets data-slot attributes', () => {
        render(
            <Collapsible>
                <CollapsibleTrigger>Toggle</CollapsibleTrigger>
                <CollapsibleContent>Content</CollapsibleContent>
            </Collapsible>,
        )
        expect(screen.getByText('Toggle')).toHaveAttribute('data-slot', 'collapsible-trigger')
    })
})