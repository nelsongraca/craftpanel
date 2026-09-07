import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {ListTh, ListTd, ListActions, IconActionButton, ListBody, ListEmpty} from '../list-table'

describe('ListTh', () => {
    it('renders children', () => {
        render(<table><thead><tr><ListTh>Name</ListTh></tr></thead></table>)
        expect(screen.getByText('Name')).toBeInTheDocument()
    })

    it('applies right alignment', () => {
        render(<table><thead><tr><ListTh align="right">Actions</ListTh></tr></thead></table>)
        expect(screen.getByText('Actions')).toHaveClass('text-right')
    })

    it('defaults to left alignment', () => {
        render(<table><thead><tr><ListTh>Name</ListTh></tr></thead></table>)
        expect(screen.getByText('Name')).toHaveClass('text-left')
    })

    it('forwards className', () => {
        render(<table><thead><tr><ListTh className="extra">Name</ListTh></tr></thead></table>)
        expect(screen.getByText('Name')).toHaveClass('extra')
    })
})

describe('ListTd', () => {
    it('renders children', () => {
        render(<table><tbody><tr><ListTd>value</ListTd></tr></tbody></table>)
        expect(screen.getByText('value')).toBeInTheDocument()
    })

    it('applies extra padding for first column', () => {
        render(<table><tbody><tr><ListTd firstCol>val</ListTd></tr></tbody></table>)
        const td = screen.getByText('val')
        expect(td.className).toContain('px-5')
    })
})

describe('ListActions', () => {
    it('renders children', () => {
        render(<table><tbody><tr><ListActions><button>Edit</button></ListActions></tr></tbody></table>)
        expect(screen.getByText('Edit')).toBeInTheDocument()
    })
})

describe('IconActionButton', () => {
    it('renders with icon and label', () => {
        render(<IconActionButton icon={<span>X</span>} label="Delete" onClick={vi.fn()}/>)
        expect(screen.getByRole('button')).toHaveAttribute('aria-label', 'Delete')
        expect(screen.getByText('X')).toBeInTheDocument()
    })

    it('fires onClick when clicked', async () => {
        const onClick = vi.fn()
        const user = userEvent.setup()
        render(<IconActionButton icon={<span>X</span>} label="Delete" onClick={onClick}/>)
        await user.click(screen.getByRole('button'))
        expect(onClick).toHaveBeenCalledTimes(1)
    })

    it('is disabled when disabled prop is set', () => {
        render(<IconActionButton icon={<span>X</span>} label="Delete" onClick={vi.fn()} disabled/>)
        expect(screen.getByRole('button')).toBeDisabled()
    })

    it('shows a spinner when loading', () => {
        render(<IconActionButton icon={<span>X</span>} label="Delete" onClick={vi.fn()} loading/>)
        const button = screen.getByRole('button')
        expect(button).toBeDisabled()
        expect(button.querySelector('.animate-spin')).toBeInTheDocument()
    })

    it('applies danger tone classes', () => {
        render(<IconActionButton icon={<span>X</span>} label="Delete" onClick={vi.fn()} danger/>)
        expect(screen.getByRole('button').className).toContain('hover:text-error')
    })
})

describe('ListBody', () => {
    it('renders children', () => {
        render(<table><ListBody><tr><td>row</td></tr></ListBody></table>)
        expect(screen.getByText('row')).toBeInTheDocument()
    })
})

describe('ListEmpty', () => {
    it('renders message', () => {
        render(<table><tbody><ListEmpty message="No items found"/></tbody></table>)
        expect(screen.getByText('No items found')).toBeInTheDocument()
    })

    it('sets colSpan', () => {
        render(<table><tbody><ListEmpty message="Empty"/></tbody></table>)
        const td = screen.getByText('Empty').closest('td')
        expect(td).toHaveAttribute('colspan', '99')
    })
})