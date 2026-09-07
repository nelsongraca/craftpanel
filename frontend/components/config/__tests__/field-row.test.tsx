import {describe, it, expect, vi} from 'vitest'
import {render, screen, fireEvent} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {FieldRow} from '../field-row'
import type {FieldDef} from '../field-types'

describe('FieldRow', () => {
    const form: Record<string, string> = {}

    it('renders label and hint', () => {
        const field: FieldDef = {key: 'MOTD', label: 'MOTD', type: 'text', hint: 'Message of the Day', serverPropertiesMapped: true}
        render(<FieldRow field={field} value="" onChange={vi.fn()} form={form} setField={vi.fn()}/>)
        expect(screen.getByText('MOTD')).toBeInTheDocument()
        expect(screen.getByText('Message of the Day')).toBeInTheDocument()
    })

    it('renders a toggle field', () => {
        const field: FieldDef = {key: 'PVP', label: 'PvP', type: 'toggle', serverPropertiesMapped: true}
        render(<FieldRow field={field} value="true" onChange={vi.fn()} form={form} setField={vi.fn()}/>)
        const toggle = screen.getByRole('switch')
        expect(toggle).toBeInTheDocument()
        expect(toggle).toBeChecked()
    })

    it('calls onChange when toggle changes', async () => {
        const onChange = vi.fn()
        const setField = vi.fn()
        const field: FieldDef = {key: 'PVP', label: 'PvP', type: 'toggle', serverPropertiesMapped: true}
        const user = userEvent.setup()
        render(<FieldRow field={field} value="false" onChange={onChange} form={form} setField={setField}/>)
        await user.click(screen.getByRole('switch'))
        expect(onChange).toHaveBeenCalledWith('true')
    })

    it('clears USE_MEOWICE_FLAGS when USE_AIKAR_FLAGS toggled on', async () => {
        const onChange = vi.fn()
        const setField = vi.fn()
        const field: FieldDef = {key: 'USE_AIKAR_FLAGS', label: 'Aikar Flags', type: 'toggle', serverPropertiesMapped: false}
        const user = userEvent.setup()
        render(<FieldRow field={field} value="false" onChange={onChange} form={{USE_MEOWICE_FLAGS: 'true'}} setField={setField}/>)
        await user.click(screen.getByRole('switch'))
        expect(setField).toHaveBeenCalledWith('USE_MEOWICE_FLAGS', 'false')
    })

    it('renders a select field', () => {
        const field: FieldDef = {key: 'DIFFICULTY', label: 'Difficulty', type: 'select', options: ['easy', 'normal', 'hard'], serverPropertiesMapped: true}
        render(<FieldRow field={field} value="easy" onChange={vi.fn()} form={form} setField={vi.fn()}/>)
        const select = screen.getByRole('combobox')
        expect(select).toBeInTheDocument()
        expect(screen.getByText('easy')).toBeInTheDocument()
    })

    it('renders a text field', () => {
        const field: FieldDef = {key: 'MOTD', label: 'MOTD', type: 'text', serverPropertiesMapped: true}
        render(<FieldRow field={field} value="hello" onChange={vi.fn()} form={form} setField={vi.fn()}/>)
        const input = screen.getByDisplayValue('hello')
        expect(input).toBeInTheDocument()
    })

    it('renders a number field', () => {
        const field: FieldDef = {key: 'VIEW_DISTANCE', label: 'View Distance', type: 'number', serverPropertiesMapped: true}
        render(<FieldRow field={field} value="10" onChange={vi.fn()} form={form} setField={vi.fn()}/>)
        const input = screen.getByDisplayValue('10')
        expect(input).toHaveAttribute('type', 'number')
    })

    it('renders a textarea field', () => {
        const field: FieldDef = {key: 'CUSTOM', label: 'Custom', type: 'textarea', serverPropertiesMapped: true}
        render(<FieldRow field={field} value="line1" onChange={vi.fn()} form={form} setField={vi.fn()}/>)
        expect(screen.getByDisplayValue('line1')).toBeInTheDocument()
    })

    it('renders a tag-input field', () => {
        const field: FieldDef = {key: 'OPS', label: 'Ops', type: 'tag-input', serverPropertiesMapped: true}
        render(<FieldRow field={field} value="admin1,admin2" onChange={vi.fn()} form={form} setField={vi.fn()}/>)
        expect(screen.getByText('admin1')).toBeInTheDocument()
        expect(screen.getByText('admin2')).toBeInTheDocument()
    })

    it('tag-input can add a new tag', async () => {
        const onChange = vi.fn()
        const field: FieldDef = {key: 'OPS', label: 'Ops', type: 'tag-input', serverPropertiesMapped: true}
        const user = userEvent.setup()
        render(<FieldRow field={field} value="admin1" onChange={onChange} form={form} setField={vi.fn()}/>)
        const input = screen.getByPlaceholderText('Add entry\u2026')
        await user.type(input, 'admin2{Enter}')
        expect(onChange).toHaveBeenCalledWith('admin1,admin2')
    })

    it('tag-input can remove a tag', async () => {
        const onChange = vi.fn()
        const field: FieldDef = {key: 'OPS', label: 'Ops', type: 'tag-input', serverPropertiesMapped: true}
        const user = userEvent.setup()
        render(<FieldRow field={field} value="admin1,admin2" onChange={onChange} form={form} setField={vi.fn()}/>)
        const removeBtn = screen.getByText('admin1').closest('span')?.querySelector('button')
        expect(removeBtn).toBeInTheDocument()
        if (removeBtn) {
            await user.click(removeBtn)
            expect(onChange).toHaveBeenCalledWith('admin2')
        }
    })
})