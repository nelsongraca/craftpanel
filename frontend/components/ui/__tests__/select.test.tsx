import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {Select, SelectTrigger, SelectValue, SelectContent, SelectItem, SelectGroup, SelectLabel} from '../select'

describe('Select', () => {
    it('renders trigger and opens content', async () => {
        const user = userEvent.setup()
        render(
            <Select defaultValue="a">
                <SelectTrigger>
                    <SelectValue/>
                </SelectTrigger>
                <SelectContent>
                    <SelectGroup>
                        <SelectLabel>Group A</SelectLabel>
                        <SelectItem value="a">Option A</SelectItem>
                        <SelectItem value="b">Option B</SelectItem>
                    </SelectGroup>
                </SelectContent>
            </Select>,
        )
        const trigger = screen.getByRole('combobox')
        expect(trigger).toBeInTheDocument()
        await user.click(trigger)
        expect(await screen.findByText('Option A')).toBeInTheDocument()
        expect(screen.getByText('Option B')).toBeInTheDocument()
        expect(screen.getByText('Group A')).toBeInTheDocument()
    })

    it('shows placeholder when no value selected', () => {
        render(
            <Select>
                <SelectTrigger>
                    <SelectValue placeholder="Pick one"/>
                </SelectTrigger>
                <SelectContent>
                    <SelectItem value="a">A</SelectItem>
                </SelectContent>
            </Select>,
        )
        expect(screen.getByText('Pick one')).toBeInTheDocument()
    })
})