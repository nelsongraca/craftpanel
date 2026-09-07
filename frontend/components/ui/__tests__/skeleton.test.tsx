import {describe, it, expect} from 'vitest'
import {render} from '@testing-library/react'
import {Skeleton} from '../skeleton'

describe('Skeleton', () => {
    it('renders with animate-pulse class', () => {
        const {container} = render(<Skeleton/>)
        const el = container.firstChild as HTMLElement
        expect(el.className).toContain('animate-pulse')
        expect(el.className).toContain('rounded-md')
    })

    it('sets data-slot attribute', () => {
        const {container} = render(<Skeleton/>)
        expect(container.firstChild).toHaveAttribute('data-slot', 'skeleton')
    })

    it('forwards className prop', () => {
        const {container} = render(<Skeleton className="h-8 w-32"/>)
        const el = container.firstChild as HTMLElement
        expect(el.className).toContain('h-8')
        expect(el.className).toContain('w-32')
    })

    it('renders as a div', () => {
        const {container} = render(<Skeleton/>)
        expect(container.firstChild?.nodeName).toBe('DIV')
    })
})