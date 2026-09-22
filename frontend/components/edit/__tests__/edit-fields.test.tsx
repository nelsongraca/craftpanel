import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {EditInput, EditSelect, EditTextarea, EditFieldRow, SaveCancelRow} from '../edit-fields'

describe('EditInput', () => {
    it('renders input with placeholder', () => {
        render(<EditInput placeholder="Server Name"/>)
        expect(screen.getByPlaceholderText('Server Name')).toBeInTheDocument()
    })
})

describe('EditSelect', () => {
    it('renders select with options', () => {
        render(
            <EditSelect value="a" onChange={vi.fn()}>
                <option value="a">A</option>
                <option value="b">B</option>
            </EditSelect>,
        )
        expect(screen.getByRole('combobox')).toBeInTheDocument()
        expect(screen.getByText('A')).toBeInTheDocument()
    })
})

describe('EditTextarea', () => {
    it('renders textarea with placeholder', () => {
        render(<EditTextarea placeholder="Description" data-testid="ta"/>)
        expect(screen.getByTestId('ta')).toHaveAttribute('placeholder', 'Description')
    })
})

describe('EditFieldRow', () => {
    it('renders label and children', () => {
        render(<EditFieldRow label="RAM"><input data-testid="ram"/></EditFieldRow>)
        expect(screen.getByText('RAM')).toBeInTheDocument()
        expect(screen.getByTestId('ram')).toBeInTheDocument()
    })
})

describe('SaveCancelRow', () => {
    it('renders Cancel and Save buttons', () => {
        render(<SaveCancelRow onSave={vi.fn()} onCancel={vi.fn()} saving={false}/>)
        expect(screen.getByText('Cancel')).toBeInTheDocument()
        expect(screen.getByText('Save')).toBeInTheDocument()
    })

    it('calls onCancel when Cancel clicked', async () => {
        const onCancel = vi.fn()
        const user = userEvent.setup()
        render(<SaveCancelRow onSave={vi.fn()} onCancel={onCancel} saving={false}/>)
        await user.click(screen.getByText('Cancel'))
        expect(onCancel).toHaveBeenCalled()
    })

    it('calls onSave when Save clicked', async () => {
        const onSave = vi.fn()
        const user = userEvent.setup()
        render(<SaveCancelRow onSave={onSave} onCancel={vi.fn()} saving={false}/>)
        await user.click(screen.getByText('Save'))
        expect(onSave).toHaveBeenCalled()
    })

    it('disables Save when saving', () => {
        render(<SaveCancelRow onSave={vi.fn()} onCancel={vi.fn()} saving={true}/>)
        expect(screen.getByText('Saving\u2026')).toBeDisabled()
    })
})