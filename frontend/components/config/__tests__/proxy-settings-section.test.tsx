import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {ProxySettingsSection} from '../proxy-settings-section'

vi.mock('@/lib/generated/sdk.gen', () => ({
    getProxySettings: vi.fn(),
    updateProxySettings: vi.fn(),
}))

import {getProxySettings, updateProxySettings} from '@/lib/generated/sdk.gen'

describe('ProxySettingsSection', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('shows loading state initially', () => {
        vi.mocked(getProxySettings).mockReturnValue(new Promise(() => {}))
        render(<ProxySettingsSection serverId="s1" serverType="VELOCITY"/>)
        expect(screen.getByText('Loading\u2026')).toBeInTheDocument()
    })

    it('renders settings after loading', async () => {
        vi.mocked(getProxySettings).mockResolvedValue({
            data: {motd: 'A Proxy', max_players: 20, forwarding_mode: 'MODERN'},
            error: undefined,
            response: new Response(),
        })
        render(<ProxySettingsSection serverId="s1" serverType="VELOCITY"/>)
        await waitFor(() => expect(screen.getByText('Proxy Settings')).toBeInTheDocument())
        expect(screen.getByDisplayValue('A Proxy')).toBeInTheDocument()
        expect(screen.getByDisplayValue('20')).toBeInTheDocument()
    })

    it('shows forwarding mode select for VELOCITY', async () => {
        vi.mocked(getProxySettings).mockResolvedValue({
            data: {motd: '', max_players: null, forwarding_mode: ''},
            error: undefined,
            response: new Response(),
        })
        render(<ProxySettingsSection serverId="s1" serverType="VELOCITY"/>)
        await waitFor(() => {
            expect(screen.getByText('Default')).toBeInTheDocument()
        })
    })

    it('shows IP Forwarding checkbox for non-VELOCITY', async () => {
        vi.mocked(getProxySettings).mockResolvedValue({
            data: {motd: '', max_players: null, forwarding_mode: ''},
            error: undefined,
            response: new Response(),
        })
        render(<ProxySettingsSection serverId="s1" serverType="BUNGEECORD"/>)
        await waitFor(() => {
            expect(screen.getByText('IP Forwarding')).toBeInTheDocument()
        })
    })

    it('calls updateProxySettings on save', async () => {
        vi.mocked(getProxySettings).mockResolvedValue({
            data: {motd: 'Hello', max_players: 20, forwarding_mode: 'NONE'},
            error: undefined,
            response: new Response(),
        })
        vi.mocked(updateProxySettings).mockResolvedValue({data: {forwarding_warnings: []}, error: undefined, response: new Response()})
        const user = userEvent.setup()
        render(<ProxySettingsSection serverId="s1" serverType="VELOCITY"/>)

        const motdInput = await screen.findByDisplayValue('Hello')
        await user.clear(motdInput)
        await user.type(motdInput, 'New MOTD')
        await user.click(screen.getByText('Save'))

        expect(updateProxySettings).toHaveBeenCalledWith({
            path: {id: 's1'},
            body: {motd: 'New MOTD', max_players: 20, forwarding_mode: 'NONE'},
        })
    })

    it('shows error on load failure', async () => {
        vi.mocked(getProxySettings).mockResolvedValue({
            error: {message: 'Load failed'},
            response: new Response(),
        })
        render(<ProxySettingsSection serverId="s1" serverType="VELOCITY"/>)
        expect(await screen.findByText('Load failed')).toBeInTheDocument()
    })

    it('shows forwarding warnings after save', async () => {
        vi.mocked(getProxySettings).mockResolvedValue({
            data: {motd: '', max_players: null, forwarding_mode: ''},
            error: undefined,
            response: new Response(),
        })
        vi.mocked(updateProxySettings).mockResolvedValue({
            data: {forwarding_warnings: ['Old forwarding config detected']},
            error: undefined,
            response: new Response(),
        })
        const user = userEvent.setup()
        render(<ProxySettingsSection serverId="s1" serverType="VELOCITY"/>)
        const motdInput = await screen.findByPlaceholderText('A Minecraft Proxy')
        await user.type(motdInput, 'x')
        await user.click(screen.getByText('Save'))
        expect(await screen.findByText('Old forwarding config detected')).toBeInTheDocument()
    })
})