import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {EditGeneral} from '../edit-general'

vi.mock('@/lib/generated/sdk.gen', () => ({
    updateServer: vi.fn(),
    updateServerExpiration: vi.fn(),
    setServerDisabled: vi.fn(),
    listNetworks: vi.fn(),
}))

import {updateServer, updateServerExpiration, setServerDisabled, listNetworks} from '@/lib/generated/sdk.gen'
import type {Server} from '@/lib/types'

const makeServer = (overrides?: Partial<Server>): Server => ({
    id: 's1',
    display_name: 'My Server',
    description: 'A cool server',
    server_type: 'PAPER',
    mc_version: '1.21',
    memory_mb: 2048,
    cpu_limit_millicores: 100,
    config_mode: 'MANAGED',
    host_port: '25565',
    node_id: 'n1',
    network_id: 'net1',
    status: 'RUNNING',
    created_at: '2026-01-01T00:00:00Z',
    exposed_externally: false,
    public_subdomain: null,
    custom_hostname: null,
    canonical_hostname: null,
    itzg_image_tag: 'latest',
    disabled: false,
    ...overrides,
})

describe('EditGeneral', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        vi.mocked(listNetworks).mockResolvedValue({
            data: [{id: 'net1', name: 'Test Network'}],
            error: undefined,
            response: new Response(),
        })
    })

    it('renders display name and description', () => {
        render(<EditGeneral server={makeServer()} permissions={['*']} onSaved={vi.fn()}/>)
        expect(screen.getByText('My Server')).toBeInTheDocument()
        expect(screen.getByText('A cool server')).toBeInTheDocument()
    })

    it('opens edit form on Edit click', async () => {
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} permissions={['*']} onSaved={vi.fn()}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        expect(screen.getByDisplayValue('My Server')).toBeInTheDocument()
        expect(screen.getByText('Save')).toBeInTheDocument()
    })

    it('calls updateServer on save', async () => {
        vi.mocked(updateServer).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const onSaved = vi.fn()
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} permissions={['*']} onSaved={onSaved}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        const nameInput = screen.getByDisplayValue('My Server')
        await user.clear(nameInput)
        await user.type(nameInput, 'New Name')
        await user.click(screen.getByText('Save'))
        expect(updateServer).toHaveBeenCalled()
        expect(onSaved).toHaveBeenCalled()
    })

    it('shows error on save failure', async () => {
        vi.mocked(updateServer).mockResolvedValue({error: {message: 'Save failed'}, response: new Response()})
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} permissions={['*']} onSaved={vi.fn()}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        const nameInput = screen.getByDisplayValue('My Server')
        await user.clear(nameInput)
        await user.type(nameInput, 'New Name')
        await user.click(screen.getByText('Save'))
        expect(await screen.findByText('Save failed')).toBeInTheDocument()
    })

    it('hides the expiry field without server.expires permission', async () => {
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} permissions={['server.view']} onSaved={vi.fn()}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        expect(screen.queryByPlaceholderText('Optional expiration date/time')).not.toBeInTheDocument()
    })

    it('hides the disabled toggle without server.disable permission', async () => {
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} permissions={['server.view']} onSaved={vi.fn()}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        expect(screen.queryByText('Disabled', {exact: true})).not.toBeInTheDocument()
    })

    it('calls setServerDisabled when the disabled toggle changes', async () => {
        vi.mocked(updateServer).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        vi.mocked(setServerDisabled).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} permissions={['*']} onSaved={vi.fn()}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        // Two switches now render (Disabled, JVM Metrics); Disabled is first.
        await user.click(screen.getAllByRole('switch')[0])
        await user.click(screen.getByText('Save'))
        expect(setServerDisabled).toHaveBeenCalled()
        const call = vi.mocked(setServerDisabled).mock.calls[0][0]
        expect(call.path).toEqual({id: 's1'})
        expect(call.body.disabled).toBe(true)
    })

    it('sends jvm_metrics_enabled when the JVM metrics toggle is turned off', async () => {
        vi.mocked(updateServer).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer({jvm_metrics_enabled: true})} permissions={['server.view']} onSaved={vi.fn()}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        await user.click(screen.getByRole('switch'))
        await user.click(screen.getByText('Save'))
        expect(updateServer).toHaveBeenCalled()
        const call = vi.mocked(updateServer).mock.calls[0][0]
        expect(call.body.jvm_metrics_enabled).toBe(false)
    })

    it('hides the JVM metrics toggle for Picolimbo servers', async () => {
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer({server_type: 'PICOLIMBO'})} permissions={['*']} onSaved={vi.fn()}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        expect(screen.queryByText('JVM Metrics', {exact: true})).not.toBeInTheDocument()
    })

    it('calls updateServerExpiration when the expiry changes', async () => {
        vi.mocked(updateServer).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        vi.mocked(updateServerExpiration).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} permissions={['*']} onSaved={vi.fn()}/>)
        await user.click(screen.getByTitle('Edit General Settings'))
        const expiryInput = screen.getByPlaceholderText('Optional expiration date/time')
        await user.clear(expiryInput)
        await user.type(expiryInput, '2027-03-15T09:30')
        await user.click(screen.getByText('Save'))
        expect(updateServerExpiration).toHaveBeenCalled()
        const call = vi.mocked(updateServerExpiration).mock.calls[0][0]
        expect(call.path).toEqual({id: 's1'})
        expect(call.body.expires_at).toMatch(/^2027-03-15T09:30/)
    })
})