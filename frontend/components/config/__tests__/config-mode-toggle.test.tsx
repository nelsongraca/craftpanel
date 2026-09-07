import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {ConfigModeToggle} from '../config-mode-toggle'

vi.mock('@/lib/generated/sdk.gen', () => ({
    updateConfigMode: vi.fn(),
}))

import {updateConfigMode} from '@/lib/generated/sdk.gen'

describe('ConfigModeToggle', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('renders managed mode description', () => {
        render(
            <ConfigModeToggle
                serverId="s1" configMode="MANAGED" onChanged={vi.fn()}
                manualDescription="Manual mode" managedDescription="Managed mode"
            />,
        )
        expect(screen.getByText('Managed mode')).toBeInTheDocument()
    })

    it('renders manual mode description', () => {
        render(
            <ConfigModeToggle
                serverId="s1" configMode="MANUAL" onChanged={vi.fn()}
                manualDescription="Manual mode" managedDescription="Managed mode"
            />,
        )
        expect(screen.getByText('Manual mode')).toBeInTheDocument()
    })

    it('shows Switch to Managed button when in manual mode', () => {
        render(
            <ConfigModeToggle
                serverId="s1" configMode="MANUAL" onChanged={vi.fn()}
                manualDescription="Manual" managedDescription="Managed"
            />,
        )
        expect(screen.getByText('Switch to Managed')).toBeInTheDocument()
    })

    it('shows Switch to Manual button when in managed mode', () => {
        render(
            <ConfigModeToggle
                serverId="s1" configMode="MANAGED" onChanged={vi.fn()}
                manualDescription="Manual" managedDescription="Managed"
            />,
        )
        expect(screen.getByText('Switch to Manual')).toBeInTheDocument()
    })

    it('calls updateConfigMode when switching to managed', async () => {
        vi.mocked(updateConfigMode).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const onChanged = vi.fn()
        const user = userEvent.setup()
        render(
            <ConfigModeToggle
                serverId="s1" configMode="MANUAL" onChanged={onChanged}
                manualDescription="Manual" managedDescription="Managed"
            />,
        )
        await user.click(screen.getByText('Switch to Managed'))
        expect(updateConfigMode).toHaveBeenCalledWith({path: {id: 's1'}, body: {config_mode: 'MANAGED'}})
    })

    it('shows error when updateConfigMode fails', async () => {
        vi.mocked(updateConfigMode).mockResolvedValue({error: {message: 'API error'}, response: new Response()})
        const user = userEvent.setup()
        render(
            <ConfigModeToggle
                serverId="s1" configMode="MANUAL" onChanged={vi.fn()}
                manualDescription="Manual" managedDescription="Managed"
            />,
        )
        await user.click(screen.getByText('Switch to Managed'))
        expect(await screen.findByText('API error')).toBeInTheDocument()
    })

    it('shows confirm dialog when switching to manual', async () => {
        vi.mocked(updateConfigMode).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const user = userEvent.setup()
        render(
            <ConfigModeToggle
                serverId="s1" configMode="MANAGED" onChanged={vi.fn()}
                manualDescription="Manual" managedDescription="Managed"
            />,
        )
        await user.click(screen.getByText('Switch to Manual'))
        expect(screen.getByText('Disable Managed Env Vars?')).toBeInTheDocument()
    })
})