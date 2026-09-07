import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {Dialog, DialogTrigger, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter, DialogClose} from '../dialog'

describe('Dialog', () => {
    it('renders trigger and shows content on click', async () => {
        const user = userEvent.setup()
        render(
            <Dialog>
                <DialogTrigger>Open</DialogTrigger>
                <DialogContent>
                    <DialogHeader>
                        <DialogTitle>Title</DialogTitle>
                        <DialogDescription>Description</DialogDescription>
                    </DialogHeader>
                </DialogContent>
            </Dialog>,
        )
        expect(screen.getByText('Open')).toBeInTheDocument()
        await user.click(screen.getByText('Open'))
        expect(await screen.findByText('Title')).toBeInTheDocument()
        expect(screen.getByText('Description')).toBeInTheDocument()
    })

    it('renders DialogFooter with children', async () => {
        const user = userEvent.setup()
        render(
            <Dialog defaultOpen>
                <DialogContent>
                    <DialogFooter data-testid="footer">
                        <button>OK</button>
                    </DialogFooter>
                </DialogContent>
            </Dialog>,
        )
        expect(await screen.findByText('OK')).toBeInTheDocument()
    })

    it('calls onOpenChange when DialogClose is clicked', async () => {
        const onOpenChange = vi.fn()
        const user = userEvent.setup()
        render(
            <Dialog defaultOpen onOpenChange={onOpenChange}>
                <DialogContent>
                    <DialogClose data-testid="close">Close</DialogClose>
                </DialogContent>
            </Dialog>,
        )
        await user.click(screen.getByTestId('close'))
        expect(onOpenChange).toHaveBeenCalled()
    })
})