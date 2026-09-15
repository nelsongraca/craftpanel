import {describe, it, expect, vi} from 'vitest'
import {render, screen, fireEvent} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {ExtraVarsSection} from '../extra-vars-section'
import type {EnvVarItem} from '@/lib/types'

describe('ExtraVarsSection', () => {
    it('renders empty state', () => {
        render(<ExtraVarsSection extraVars={[]} onUpdate={vi.fn()} onRemove={vi.fn()} onAdd={vi.fn()}/>)
        expect(screen.getByText('No extra variables.')).toBeInTheDocument()
    })

    it('renders variable rows', () => {
        const vars: EnvVarItem[] = [{key: 'FOO', value: 'bar'}]
        render(<ExtraVarsSection extraVars={vars} onUpdate={vi.fn()} onRemove={vi.fn()} onAdd={vi.fn()}/>)
        expect(screen.getByDisplayValue('FOO')).toBeInTheDocument()
        expect(screen.getByDisplayValue('bar')).toBeInTheDocument()
    })

    it('calls onAdd when Add button clicked', async () => {
        const onAdd = vi.fn()
        const user = userEvent.setup()
        render(<ExtraVarsSection extraVars={[]} onUpdate={vi.fn()} onRemove={vi.fn()} onAdd={onAdd}/>)
        await user.click(screen.getByText('Add'))
        expect(onAdd).toHaveBeenCalled()
    })

    it('calls onRemove when remove button clicked', async () => {
        const onRemove = vi.fn()
        const vars: EnvVarItem[] = [{key: 'FOO', value: 'bar'}]
        const user = userEvent.setup()
        render(<ExtraVarsSection extraVars={vars} onUpdate={vi.fn()} onRemove={onRemove} onAdd={vi.fn()}/>)
        const removeBtn = screen.getByRole('button', {name: ''})
        await user.click(removeBtn)
        expect(onRemove).toHaveBeenCalledWith(0)
    })

    it('calls onUpdate when key field changes', () => {
        const onUpdate = vi.fn()
        const vars: EnvVarItem[] = [{key: 'FOO', value: 'bar'}]
        render(<ExtraVarsSection extraVars={vars} onUpdate={onUpdate} onRemove={vi.fn()} onAdd={vi.fn()}/>)
        const keyInput = screen.getByDisplayValue('FOO')
        fireEvent.change(keyInput, {target: {value: 'BAR'}})
        expect(onUpdate).toHaveBeenCalledWith(0, 'key', 'BAR')
    })

    it('calls onUpdate when value field changes', () => {
        const onUpdate = vi.fn()
        const vars: EnvVarItem[] = [{key: 'FOO', value: 'bar'}]
        render(<ExtraVarsSection extraVars={vars} onUpdate={onUpdate} onRemove={vi.fn()} onAdd={vi.fn()}/>)
        const valueInput = screen.getByDisplayValue('bar')
        fireEvent.change(valueInput, {target: {value: 'baz'}})
        expect(onUpdate).toHaveBeenCalledWith(0, 'value', 'baz')
    })

    it('renders multi-line value in textarea', () => {
        const vars: EnvVarItem[] = [{key: 'CERT', value: 'line1\nline2\nline3'}]
        render(<ExtraVarsSection extraVars={vars} onUpdate={vi.fn()} onRemove={vi.fn()} onAdd={vi.fn()}/>)
        const textarea = screen.getByDisplayValue('line1\nline2\nline3', {collapseWhitespace: false, trim: false})
        expect(textarea.tagName).toBe('TEXTAREA')
    })

    it('calls onUpdate with multi-line value on textarea change', () => {
        const onUpdate = vi.fn()
        const vars: EnvVarItem[] = [{key: 'CERT', value: 'old'}]
        render(<ExtraVarsSection extraVars={vars} onUpdate={onUpdate} onRemove={vi.fn()} onAdd={vi.fn()}/>)
        const textarea = screen.getByDisplayValue('old')
        fireEvent.change(textarea, {target: {value: 'new\nline2'}})
        expect(onUpdate).toHaveBeenCalledWith(0, 'value', 'new\nline2')
    })

    it('key field remains a single-line input', () => {
        const vars: EnvVarItem[] = [{key: 'FOO', value: 'bar'}]
        render(<ExtraVarsSection extraVars={vars} onUpdate={vi.fn()} onRemove={vi.fn()} onAdd={vi.fn()}/>)
        const keyInput = screen.getByDisplayValue('FOO')
        expect(keyInput.tagName).toBe('INPUT')
    })
})
