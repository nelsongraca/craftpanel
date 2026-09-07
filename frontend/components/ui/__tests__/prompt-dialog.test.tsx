import {describe, it, expect, vi} from 'vitest'
import {render, screen, fireEvent} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {PromptDialog} from '../prompt-dialog'

describe('PromptDialog', () => {
    it('renders title, description, label and input when open', () => {
        render(
            <PromptDialog
                open
                onOpenChange={vi.fn()}
                title="Enter Name"
                description="Choose a name for the server"
                label="Server Name"
                onConfirm={vi.fn()}
            />,
        )
        expect(screen.getByText('Enter Name')).toBeInTheDocument()
        expect(screen.getByText('Choose a name for the server')).toBeInTheDocument()
        expect(screen.getByText('Server Name')).toBeInTheDocument()
        expect(screen.getByRole('textbox')).toBeInTheDocument()
    })

    it('calls onConfirm with the input value', () => {
        const onConfirm = vi.fn()
        render(
            <PromptDialog
                open
                onOpenChange={vi.fn()}
                title="Enter Name"
                label="Name"
                onConfirm={onConfirm}
            />,
        )
        const input = screen.getByRole('textbox')
        fireEvent.change(input, {target: {value: 'my-server'}})
        fireEvent.click(screen.getByText('Confirm'))
        expect(onConfirm).toHaveBeenCalledWith('my-server')
    })

    it('resets value to defaultValue when opened', () => {
        const {rerender} = render(
            <PromptDialog
                open={false}
                onOpenChange={vi.fn()}
                title="Rename"
                label="Name"
                defaultValue="old-name"
                onConfirm={vi.fn()}
            />,
        )
        rerender(
            <PromptDialog
                open
                onOpenChange={vi.fn()}
                title="Rename"
                label="Name"
                defaultValue="old-name"
                onConfirm={vi.fn()}
            />,
        )
        expect(screen.getByRole('textbox')).toHaveValue('old-name')
    })

    it('calls onOpenChange on Cancel click', async () => {
        const onOpenChange = vi.fn()
        const user = userEvent.setup()
        render(
            <PromptDialog
                open
                onOpenChange={onOpenChange}
                title="Enter Name"
                label="Name"
                onConfirm={vi.fn()}
            />,
        )
        await user.click(screen.getByText('Cancel'))
        expect(onOpenChange).toHaveBeenCalled()
    })

    it('calls onConfirm on Enter key', () => {
        const onConfirm = vi.fn()
        render(
            <PromptDialog
                open
                onOpenChange={vi.fn()}
                title="Enter Name"
                label="Name"
                onConfirm={onConfirm}
            />,
        )
        const input = screen.getByRole('textbox')
        fireEvent.change(input, {target: {value: 'test'}})
        fireEvent.keyDown(input, {key: 'Enter'})
        expect(onConfirm).toHaveBeenCalledWith('test')
    })

    it('disables Confirm button when input is empty', () => {
        render(
            <PromptDialog
                open
                onOpenChange={vi.fn()}
                title="Enter Name"
                label="Name"
                onConfirm={vi.fn()}
            />,
        )
        expect(screen.getByText('Confirm')).toBeDisabled()
    })
})