import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {EditResources} from '../edit-resources'

vi.mock('@/lib/generated/sdk.gen', () => ({
    updateServerResources: vi.fn(),
}))

import {updateServerResources} from '@/lib/generated/sdk.gen'
import type {Server} from '@/lib/types'

const makeServer = (overrides?: Partial<Server>): Server => ({
    id: 's1',
    display_name: 'Test',
    description: null,
    server_type: 'PAPER',
    mc_version: '1.21',
    memory_mb: 2048,
    cpu_shares: 100,
    config_mode: 'MANAGED',
    host_port: '25565',
    node_id: 'n1',
    network_id: null,
    status: 'RUNNING',
    created_at: '2026-01-01T00:00:00Z',
    exposed_externally: false,
    public_subdomain: null,
    custom_hostname: null,
    canonical_hostname: null,
    itzg_image_tag: 'latest',
    ...overrides,
})

describe('EditResources', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('renders resource info', () => {
        render(<EditResources server={makeServer()} onSaved={vi.fn()}/>)
        expect(screen.getByText('2048 MB')).toBeInTheDocument()
        expect(screen.getByText('100')).toBeInTheDocument()
        expect(screen.getByText('latest')).toBeInTheDocument()
    })

    it('opens edit form on Edit click', async () => {
        const user = userEvent.setup()
        render(<EditResources server={makeServer()} onSaved={vi.fn()}/>)
        await user.click(screen.getByText('Edit'))
        expect(screen.getByDisplayValue('2048')).toBeInTheDocument()
        expect(screen.getByDisplayValue('100')).toBeInTheDocument()
    })

    it('calls updateServerResources on save', async () => {
        vi.mocked(updateServerResources).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const onSaved = vi.fn()
        const user = userEvent.setup()
        render(<EditResources server={makeServer()} onSaved={onSaved}/>)
        await user.click(screen.getByText('Edit'))
        const ramInput = screen.getByDisplayValue('2048')
        await user.clear(ramInput)
        await user.type(ramInput, '4096')
        await user.click(screen.getByText('Save'))
        expect(updateServerResources).toHaveBeenCalledWith({
            path: {id: 's1'},
            body: {memory_mb: 4096, cpu_shares: 100, itzg_image_tag: 'latest'},
        })
        expect(onSaved).toHaveBeenCalled()
    })

    it('shows error on save failure', async () => {
        vi.mocked(updateServerResources).mockResolvedValue({error: {message: 'Update failed'}, response: new Response()})
        const user = userEvent.setup()
        render(<EditResources server={makeServer()} onSaved={vi.fn()}/>)
        await user.click(screen.getByText('Edit'))
        await user.click(screen.getByText('Save'))
        expect(await screen.findByText('Update failed')).toBeInTheDocument()
    })
})