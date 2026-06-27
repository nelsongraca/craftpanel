import { describe, it, expect, vi } from 'vitest'
import { render } from '@testing-library/react'
import { useClickOutside } from '../useClickOutside'

function Test({ onClick }: { onClick: () => void }) {
    const ref = useClickOutside(onClick)
    return <div ref={ref} data-testid="inside">content</div>
}

describe('useClickOutside', () => {
    it('renders with ref attached to a div', () => {
        const onClick = vi.fn()
        const { getByTestId } = render(<Test onClick={onClick} />)
        expect(getByTestId('inside')).toBeTruthy()
    })

    it('clicking inside does not call onClick', () => {
        const onClick = vi.fn()
        const { getByTestId } = render(<Test onClick={onClick} />)
        getByTestId('inside').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
        expect(onClick).not.toHaveBeenCalled()
    })

    it('clicking outside calls onClick', () => {
        const onClick = vi.fn()
        render(<Test onClick={onClick} />)
        document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
        expect(onClick).toHaveBeenCalledTimes(1)
    })

    it('cleanup removes event listener', () => {
        const onClick = vi.fn()
        const { unmount } = render(<Test onClick={onClick} />)
        unmount()
        document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }))
        expect(onClick).not.toHaveBeenCalled()
    })
})
