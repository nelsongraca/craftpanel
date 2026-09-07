import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {EditExposure} from '../edit-exposure'

vi.mock('@/lib/generated/sdk.gen', () => ({
    updateServerExposure: vi.fn(),
}))

import {updateServerExposure} from '@/lib/generated/sdk.gen'
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

describe('EditExposure', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('renders exposure info', () => {
        render(<EditExposure server={makeServer()} onSaved={vi.fn()}/>)
        expect(screen.getByText('No')).toBeInTheDocument()
    })

    it('shows exposed info when server is exposed', () => {
        render(
            <EditExposure
                server={makeServer({exposed_externally: true, public_subdomain: 'myserver', custom_hostname: 'play.example.com', canonical_hostname: 'myserver.mc.example.com'})}
                onSaved={vi.fn()}
            />,
        )
        expect(screen.getByText('Yes')).toBeInTheDocument()
        expect(screen.getByText('myserver')).toBeInTheDocument()
        expect(screen.getByText('play.example.com')).toBeInTheDocument()
    })

    it('opens edit form on Edit click', async () => {
        const user = userEvent.setup()
        render(<EditExposure server={makeServer()} onSaved={vi.fn()}/>)
        await user.click(screen.getByText('Edit'))
        expect(screen.getByText('Expose Externally')).toBeInTheDocument()
    })

    it('calls updateServerExposure on save', async () => {
        vi.mocked(updateServerExposure).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const onSaved = vi.fn()
        const user = userEvent.setup()
        render(<EditExposure server={makeServer()} onSaved={onSaved}/>)
        await user.click(screen.getByText('Edit'))
        await user.click(screen.getByText('Save'))
        expect(updateServerExposure).toHaveBeenCalledWith({
            path: {id: 's1'},
            body: {exposed_externally: false, public_subdomain: null, custom_hostname: null},
        })
        expect(onSaved).toHaveBeenCalled()
    })

    it('shows subdomain fields when exposed checked', async () => {
        vi.mocked(updateServerExposure).mockResolvedValue({data: {}, error: undefined, response: new Response()})
        const user = userEvent.setup()
        render(<EditExposure server={makeServer()} onSaved={vi.fn()}/>)
        await user.click(screen.getByText('Edit'))
        await user.click(screen.getByLabelText('Expose via mc-router'))
        expect(screen.getByText('Public Subdomain')).toBeInTheDocument()
        expect(screen.getByText('Custom Hostname')).toBeInTheDocument()
    })
})