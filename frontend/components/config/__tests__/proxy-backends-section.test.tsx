import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {ProxyBackendsSection} from '../proxy-backends-section'

vi.mock('@/lib/generated/sdk.gen', () => ({
    getProxyBackends: vi.fn(),
    listServers: vi.fn(),
    replaceProxyBackends: vi.fn(),
}))

import {getProxyBackends, listServers, replaceProxyBackends} from '@/lib/generated/sdk.gen'
import type {ServerResponse} from '@/lib/generated/types.gen'

const makeServer = (id: string, name: string, type: string, status: string): ServerResponse => ({
    id,
    display_name: name,
    server_type: type,
    status,
    node_id: 'node1',
    network_id: 'net1',
    mc_version: '1.21',
    memory_mb: 2048,
    cpu_shares: 100,
    config_mode: 'MANAGED',
    host_port: '25565',
    created_at: '2026-01-01T00:00:00Z',
    exposed_externally: false,
    itzg_image_tag: 'latest',
})

describe('ProxyBackendsSection', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('shows loading state initially', () => {
        vi.mocked(getProxyBackends).mockReturnValue(new Promise(() => {}))
        vi.mocked(listServers).mockReturnValue(new Promise(() => {}))
        render(<ProxyBackendsSection serverId="s1" networkId="net1"/>)
        expect(screen.getByText('Loading\u2026')).toBeInTheDocument()
    })

    it('renders empty state when no backends', async () => {
        vi.mocked(getProxyBackends).mockResolvedValue({data: {backends: []}, error: undefined, response: new Response()})
        vi.mocked(listServers).mockResolvedValue({data: [], error: undefined, response: new Response()})
        render(<ProxyBackendsSection serverId="s1" networkId="net1"/>)
        expect(await screen.findByText('No backends configured.')).toBeInTheDocument()
    })

    it('renders backends table', async () => {
        vi.mocked(getProxyBackends).mockResolvedValue({
            data: {
                backends: [
                    {id: 'b1', backend_server_id: 's2', backend_name: 'survival', order: 1},
                ],
            },
            error: undefined,
            response: new Response(),
        })
        vi.mocked(listServers).mockResolvedValue({
            data: [makeServer('s2', 'Survival World', 'PAPER', 'RUNNING')],
            error: undefined,
            response: new Response(),
        })
        render(<ProxyBackendsSection serverId="s1" networkId="net1"/>)
        const input = await screen.findByDisplayValue('survival')
        expect(input).toBeInTheDocument()
        expect(screen.getByText('Survival World')).toBeInTheDocument()
    })

    it('shows warning when no network', async () => {
        vi.mocked(getProxyBackends).mockResolvedValue({data: {backends: []}, error: undefined, response: new Response()})
        vi.mocked(listServers).mockResolvedValue({data: [], error: undefined, response: new Response()})
        render(<ProxyBackendsSection serverId="s1" networkId={null}/>)
        expect(await screen.findByText(/not in a network/)).toBeInTheDocument()
    })

    it('opens add backend modal', async () => {
        vi.mocked(getProxyBackends).mockResolvedValue({data: {backends: []}, error: undefined, response: new Response()})
        vi.mocked(listServers).mockResolvedValue({
            data: [makeServer('s2', 'Survival', 'PAPER', 'RUNNING')],
            error: undefined,
            response: new Response(),
        })
        const user = userEvent.setup()
        render(<ProxyBackendsSection serverId="s1" networkId="net1"/>)
        expect(await screen.findByText('No backends configured.')).toBeInTheDocument()
        await user.click(screen.getByRole('button', {name: /add backend/i}))
        expect(await screen.findByRole('heading', {name: /add backend/i})).toBeInTheDocument()
    })

    it('calls replaceProxyBackends on save', async () => {
        vi.mocked(getProxyBackends).mockResolvedValue({
            data: {
                backends: [
                    {id: 'b1', backend_server_id: 's2', backend_name: 'survival', order: 1},
                ],
            },
            error: undefined,
            response: new Response(),
        })
        vi.mocked(listServers).mockResolvedValue({
            data: [makeServer('s2', 'Survival', 'PAPER', 'RUNNING')],
            error: undefined,
            response: new Response(),
        })
        vi.mocked(replaceProxyBackends).mockResolvedValue({data: {forwarding_warnings: []}, error: undefined, response: new Response()})
        const user = userEvent.setup()
        render(<ProxyBackendsSection serverId="s1" networkId="net1"/>)
        const input = await screen.findByDisplayValue('survival')
        await user.clear(input)
        await user.type(input, 'new-name')
        await user.click(screen.getByText('Save'))
        expect(replaceProxyBackends).toHaveBeenCalled()
    })

    it('shows error when save fails with duplicate names', async () => {
        vi.mocked(getProxyBackends).mockResolvedValue({
            data: {
                backends: [
                    {id: 'b1', backend_server_id: 's2', backend_name: 'survival', order: 1},
                    {id: 'b2', backend_server_id: 's3', backend_name: 'creative', order: 2},
                ],
            },
            error: undefined,
            response: new Response(),
        })
        vi.mocked(listServers).mockResolvedValue({
            data: [
                makeServer('s2', 'Survival', 'PAPER', 'RUNNING'),
                makeServer('s3', 'Creative', 'PAPER', 'RUNNING'),
            ],
            error: undefined,
            response: new Response(),
        })
        const user = userEvent.setup()
        render(<ProxyBackendsSection serverId="s1" networkId="net1"/>)
        const input = await screen.findByDisplayValue('survival')
        await user.clear(input)
        await user.type(input, 'creative')
        await user.click(screen.getByText('Save'))
        expect(await screen.findByText('Backend names must be unique')).toBeInTheDocument()
    })
})