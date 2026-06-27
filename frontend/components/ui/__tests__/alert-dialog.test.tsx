import { describe, it, expect, vi } from 'vitest'
import { render, screen, cleanup } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogPortal,
    AlertDialogTitle,
    AlertDialogTrigger,
} from '../alert-dialog'

describe('AlertDialog', () => {
    it('renders children', () => {
        render(
            <AlertDialog open>
                <AlertDialogTrigger>Open</AlertDialogTrigger>
            </AlertDialog>
        )
        expect(screen.getByText('Open')).toBeInTheDocument()
    })

    it('AlertDialogTrigger renders trigger element', () => {
        render(
            <AlertDialog open>
                <AlertDialogTrigger data-testid="trigger">Open</AlertDialogTrigger>
            </AlertDialog>
        )
        const trigger = screen.getByTestId('trigger')
        expect(trigger).toHaveAttribute('data-slot', 'alert-dialog-trigger')
    })

    it('AlertDialogContent renders title, description, close button when open', () => {
        render(
            <AlertDialog open>
                <AlertDialogPortal>
                    <AlertDialogContent>
                        <AlertDialogTitle>title</AlertDialogTitle>
                        <AlertDialogDescription>desc</AlertDialogDescription>
                        <AlertDialogCancel>Cancel</AlertDialogCancel>
                        <AlertDialogAction>Confirm</AlertDialogAction>
                    </AlertDialogContent>
                </AlertDialogPortal>
            </AlertDialog>
        )
        expect(screen.getByText('title')).toBeInTheDocument()
        expect(screen.getByText('desc')).toBeInTheDocument()
        expect(screen.getByText('Cancel')).toBeInTheDocument()
        expect(screen.getByText('Confirm')).toBeInTheDocument()
    })

    it('AlertDialogCancel fires onOpenChange(false)', async () => {
        const onOpenChange = vi.fn()
        const user = userEvent.setup()
        render(
            <AlertDialog open onOpenChange={onOpenChange}>
                <AlertDialogPortal>
                    <AlertDialogContent>
                        <AlertDialogCancel>Cancel</AlertDialogCancel>
                    </AlertDialogContent>
                </AlertDialogPortal>
            </AlertDialog>
        )
        await user.click(screen.getByText('Cancel'))
        expect(onOpenChange).toHaveBeenCalledWith(false, expect.anything())
    })

    it('AlertDialogAction fires onClick', async () => {
        const onClick = vi.fn()
        const user = userEvent.setup()
        render(
            <AlertDialog open>
                <AlertDialogPortal>
                    <AlertDialogContent>
                        <AlertDialogAction onClick={onClick}>Confirm</AlertDialogAction>
                    </AlertDialogContent>
                </AlertDialogPortal>
            </AlertDialog>
        )
        await user.click(screen.getByText('Confirm'))
        expect(onClick).toHaveBeenCalledTimes(1)
    })

    it('data-size attribute on content for "sm" vs default', () => {
        render(
            <AlertDialog open>
                <AlertDialogPortal>
                    <AlertDialogContent size="sm">
                        <AlertDialogTitle>sm title</AlertDialogTitle>
                    </AlertDialogContent>
                </AlertDialogPortal>
            </AlertDialog>
        )
        expect(
            screen.getByText('sm title').closest('[data-slot="alert-dialog-content"]'),
        ).toHaveAttribute('data-size', 'sm')

        cleanup()

        render(
            <AlertDialog open>
                <AlertDialogPortal>
                    <AlertDialogContent>
                        <AlertDialogTitle>default title</AlertDialogTitle>
                    </AlertDialogContent>
                </AlertDialogPortal>
            </AlertDialog>
        )
        expect(
            screen.getByText('default title').closest('[data-slot="alert-dialog-content"]'),
        ).toHaveAttribute('data-size', 'default')
    })
})
