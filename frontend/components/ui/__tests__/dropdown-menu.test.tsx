import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
    DropdownMenu,
    DropdownMenuTrigger,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuLabel,
    DropdownMenuGroup,
} from '../dropdown-menu'

describe('DropdownMenu', () => {
    it('renders trigger and opens content on click', async () => {
        const user = userEvent.setup()
        render(
            <DropdownMenu>
                <DropdownMenuTrigger>Menu</DropdownMenuTrigger>
                <DropdownMenuContent>
                    <DropdownMenuItem>Item 1</DropdownMenuItem>
                    <DropdownMenuSeparator/>
                    <DropdownMenuItem>Item 2</DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>,
        )
        expect(screen.getByText('Menu')).toBeInTheDocument()
        await user.click(screen.getByText('Menu'))
        expect(await screen.findByText('Item 1')).toBeInTheDocument()
        expect(screen.getByText('Item 2')).toBeInTheDocument()
    })

    it('renders label', async () => {
        const user = userEvent.setup()
        render(
            <DropdownMenu>
                <DropdownMenuTrigger>Menu</DropdownMenuTrigger>
                <DropdownMenuContent>
                    <DropdownMenuGroup>
                        <DropdownMenuLabel>Group Label</DropdownMenuLabel>
                        <DropdownMenuItem>Item</DropdownMenuItem>
                    </DropdownMenuGroup>
                </DropdownMenuContent>
            </DropdownMenu>,
        )
        await user.click(screen.getByText('Menu'))
        expect(await screen.findByText('Group Label')).toBeInTheDocument()
    })

    it('renders a clickable item', async () => {
        const onClick = vi.fn()
        const user = userEvent.setup()
        render(
            <DropdownMenu>
                <DropdownMenuTrigger>Menu</DropdownMenuTrigger>
                <DropdownMenuContent>
                    <DropdownMenuItem onClick={onClick}>Clickable</DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>,
        )
        await user.click(screen.getByText('Menu'))
        await user.click(await screen.findByText('Clickable'))
        expect(onClick).toHaveBeenCalled()
    })
})