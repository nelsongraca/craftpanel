import {describe, it, expect, vi} from 'vitest'
import {render, screen} from '@testing-library/react'
import {OverviewTab} from '../overview-tab'
import type {Server, Node, Network} from '@/lib/types'

vi.mock('@/lib/permissions', () => ({
    hasPermission: (perms: string[], node: string) => perms.includes(node),
}))

vi.mock('@/lib/utils/format', () => ({
    timeAgo: () => '2h ago',
    fmtMb: () => '1.0 GB',
    fmtBytes: () => '1 KB',
}))

const makeServer = (overrides?: Partial<Server>): Server => ({
    id: 's1',
    display_name: 'Test Server',
    description: 'A test',
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

const makeNode = (): Node => ({
    id: 'n1',
    display_name: 'Node 1',
    hostname: 'node1.example.com',
    last_seen_at: '2026-09-07T00:00:00Z',
    status: 'ONLINE',
})

const makeNetwork = (): Network => ({
    id: 'net1',
    name: 'Test Network',
})

describe('OverviewTab', () => {
    it('renders server info section', () => {
        render(
            <OverviewTab
                server={makeServer()}
                node={makeNode()}
                network={makeNetwork()}
                permissions={['server.view']}
                liveMetrics={null}
                livePlayers={null}
                onSaved={vi.fn()}
            />,
        )
        expect(screen.getByText('Server Info')).toBeInTheDocument()
        expect(screen.getByText('PAPER')).toBeInTheDocument()
        expect(screen.getByText('Node 1')).toBeInTheDocument()
        expect(screen.getByText('Test Network')).toBeInTheDocument()
    })

    it('renders General Settings when has server.configure permission', () => {
        render(
            <OverviewTab
                server={makeServer()}
                node={makeNode()}
                network={makeNetwork()}
                permissions={['server.configure']}
                liveMetrics={null}
                livePlayers={null}
                onSaved={vi.fn()}
            />,
        )
        expect(screen.getByText('General Settings')).toBeInTheDocument()
    })

    it('does not render General Settings without server.configure permission', () => {
        render(
            <OverviewTab
                server={makeServer()}
                node={makeNode()}
                network={makeNetwork()}
                permissions={['server.view']}
                liveMetrics={null}
                livePlayers={null}
                onSaved={vi.fn()}
            />,
        )
        expect(screen.queryByText('General Settings')).not.toBeInTheDocument()
    })

    it('renders Resources when has server.resources permission', () => {
        render(
            <OverviewTab
                server={makeServer()}
                node={makeNode()}
                network={makeNetwork()}
                permissions={['server.resources']}
                liveMetrics={null}
                livePlayers={null}
                onSaved={vi.fn()}
            />,
        )
        expect(screen.getByText('Resources')).toBeInTheDocument()
    })

    it('does not render Resources without server.resources permission', () => {
        render(
            <OverviewTab
                server={makeServer()}
                node={makeNode()}
                network={makeNetwork()}
                permissions={['server.view']}
                liveMetrics={null}
                livePlayers={null}
                onSaved={vi.fn()}
            />,
        )
        expect(screen.queryByText('Resources')).not.toBeInTheDocument()
    })

    it('renders Public Access when has server.configure permission', () => {
        render(
            <OverviewTab
                server={makeServer()}
                node={makeNode()}
                network={makeNetwork()}
                permissions={['server.configure']}
                liveMetrics={null}
                livePlayers={null}
                onSaved={vi.fn()}
            />,
        )
        expect(screen.getByText('Public Access')).toBeInTheDocument()
    })
})