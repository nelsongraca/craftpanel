import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {StopCommandSection} from '../stop-command-section'

vi.mock('@/lib/generated/sdk.gen', () => ({
    updateStopCommand: vi.fn(),
}))

import {updateStopCommand} from '@/lib/generated/sdk.gen'

describe('StopCommandSection', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('renders title and default value', () => {
        render(<StopCommandSection serverId="s1" stopCommand="stop"/>)
        expect(screen.getByText('Stop Command')).toBeInTheDocument()
        expect(screen.getByDisplayValue('stop')).toBeInTheDocument()
    })

    it('shows placeholder text', () => {
        render(<StopCommandSection serverId="s1" stopCommand="" placeholder="custom-stop"/>)
        expect(screen.getByPlaceholderText('custom-stop')).toBeInTheDocument()
    })

    it('does not show Save button when value matches saved', () => {
        render(<StopCommandSection serverId="s1" stopCommand="stop"/>)
        expect(screen.queryByText('Save')).not.toBeInTheDocument()
    })

    it('shows Save button when value changes', async () => {
        const user = userEvent.setup()
        render(<StopCommandSection serverId="s1" stopCommand="stop"/>)
        const input = screen.getByDisplayValue('stop')
        await user.clear(input)
        await user.type(input, 'save-all')
        expect(screen.getByText('Save')).toBeInTheDocument()
    })

    it('calls updateStopCommand on save', async () => {
        vi.mocked(updateStopCommand).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const user = userEvent.setup()
        render(<StopCommandSection serverId="s1" stopCommand="stop"/>)
        const input = screen.getByDisplayValue('stop')
        await user.clear(input)
        await user.type(input, 'save-all')
        await user.click(screen.getByText('Save'))
        expect(updateStopCommand).toHaveBeenCalledWith({path: {id: 's1'}, body: {stop_command: 'save-all'}})
    })

    it('shows error when save fails', async () => {
        vi.mocked(updateStopCommand).mockResolvedValue({error: {message: 'Save failed'}, response: new Response()})
        const user = userEvent.setup()
        render(<StopCommandSection serverId="s1" stopCommand="stop"/>)
        const input = screen.getByDisplayValue('stop')
        await user.clear(input)
        await user.type(input, 'save-all')
        await user.click(screen.getByText('Save'))
        expect(await screen.findByText('Save failed')).toBeInTheDocument()
    })
})