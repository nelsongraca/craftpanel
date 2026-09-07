import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {EditGeneral} from '../edit-general'

vi.mock('@/lib/generated/sdk.gen', () => ({
    updateServer: vi.fn(),
    listNetworks: vi.fn(),
}))

import {updateServer, listNetworks} from '@/lib/generated/sdk.gen'
import type {Server} from '@/lib/types'

const makeServer = (overrides?: Partial<Server>): Server => ({
    id: 's1',
    display_name: 'My Server',
    description: 'A cool server',
    server_type: 'PAPER',
    mc_version: '1.21',
    memory_mb: 2048,
    cpu_shares: 100,
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
        render(<EditGeneral server={makeServer()} onSaved={vi.fn()}/>)
        expect(screen.getByText('My Server')).toBeInTheDocument()
        expect(screen.getByText('A cool server')).toBeInTheDocument()
    })

    it('opens edit form on Edit click', async () => {
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} onSaved={vi.fn()}/>)
        await user.click(screen.getByText('Edit'))
        expect(screen.getByDisplayValue('My Server')).toBeInTheDocument()
        expect(screen.getByText('Save')).toBeInTheDocument()
    })

    it('calls updateServer on save', async () => {
        vi.mocked(updateServer).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const onSaved = vi.fn()
        const user = userEvent.setup()
        render(<EditGeneral server={makeServer()} onSaved={onSaved}/>)
        await user.click(screen.getByText('Edit'))
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
        render(<EditGeneral server={makeServer()} onSaved={vi.fn()}/>)
        await user.click(screen.getByText('Edit'))
        const nameInput = screen.getByDisplayValue('My Server')
        await user.clear(nameInput)
        await user.type(nameInput, 'New Name')
        await user.click(screen.getByText('Save'))
        expect(await screen.findByText('Save failed')).toBeInTheDocument()
    })
})