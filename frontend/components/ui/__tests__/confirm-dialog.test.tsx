import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ConfirmDialog } from '../confirm-dialog'

describe('ConfirmDialog', () => {
    it('renders title and description when open', () => {
        render(
            <ConfirmDialog
                open
                onOpenChange={vi.fn()}
                title="Delete server?"
                description="This action cannot be undone."
                onConfirm={vi.fn()}
            />,
        )
        expect(screen.getByText('Delete server?')).toBeInTheDocument()
        expect(screen.getByText('This action cannot be undone.')).toBeInTheDocument()
    })

    it('Cancel button fires onOpenChange(false)', async () => {
        const onOpenChange = vi.fn()
        const user = userEvent.setup()
        render(
            <ConfirmDialog
                open
                onOpenChange={onOpenChange}
                title="Title"
                description="Desc"
                onConfirm={vi.fn()}
            />,
        )
        await user.click(screen.getByText('Cancel'))
        expect(onOpenChange).toHaveBeenCalledWith(false, expect.anything())
    })

    it('Delete (destructive) button fires onConfirm then onOpenChange(false)', async () => {
        const onConfirm = vi.fn()
        const onOpenChange = vi.fn()
        const user = userEvent.setup()
        render(
            <ConfirmDialog
                open
                onOpenChange={onOpenChange}
                title="Delete?"
                description="Sure?"
                destructive
                confirmLabel="Delete"
                onConfirm={onConfirm}
            />,
        )
        await user.click(screen.getByText('Delete'))
        expect(onConfirm).toHaveBeenCalledTimes(1)
        expect(onOpenChange).toHaveBeenCalledWith(false)
    })

    it('renders non-destructive variant with default confirm label', () => {
        render(
            <ConfirmDialog
                open
                onOpenChange={vi.fn()}
                title="Confirm?"
                description="Are you sure?"
                onConfirm={vi.fn()}
            />,
        )
        expect(screen.getByText('Confirm')).toBeInTheDocument()
    })

    it('Cancel button renders with onCancel behavior', async () => {
        const onOpenChange = vi.fn()
        const user = userEvent.setup()
        render(
            <ConfirmDialog
                open
                onOpenChange={onOpenChange}
                title="Title"
                description="Desc"
                onConfirm={vi.fn()}
            />,
        )
        const cancelButton = screen.getByText('Cancel')
        expect(cancelButton).toBeInTheDocument()
        await user.click(cancelButton)
        expect(onOpenChange).toHaveBeenCalledWith(false, expect.anything())
    })
})
