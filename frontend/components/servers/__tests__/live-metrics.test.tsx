import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {LiveMetricsPanel} from '../live-metrics'
import type {Server, Node} from '@/lib/types'
import type React from 'react'

vi.mock('@/lib/utils/format', () => ({
    fmtMb: (mb: number) => `${(mb / 1024).toFixed(1)} GB`,
    fmtBytes: (b: number) => `${(b / 1024).toFixed(0)} KB`,
    timeAgo: () => '2h ago',
}))

vi.mock('@/lib/status', () => ({
    serverStatusClass: () => 'text-healthy',
    serverStatusLabel: () => 'Running',
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

describe('LiveMetricsPanel', () => {
    it('renders Players Online stat card', () => {
        render(
            <LiveMetricsPanel
                liveMetrics={null}
                livePlayers={{count: 5, list: ['a', 'b']}}
                server={makeServer()}
                node={makeNode()}
            />,
        )
        expect(screen.getByText('Players Online')).toBeInTheDocument()
        expect(screen.getByText('5')).toBeInTheDocument()
    })

    it('renders RAM Usage card with RamBarInline', () => {
        render(
            <LiveMetricsPanel
                liveMetrics={{cpuPercent: 50, ramUsedMb: 1024, netInBytes: 1000, netOutBytes: 500}}
                livePlayers={null}
                server={makeServer({memory_mb: 2048})}
                node={makeNode()}
            />,
        )
        expect(screen.getByText('RAM Usage')).toBeInTheDocument()
    })

    it('renders CPU Usage with percentage', () => {
        render(
            <LiveMetricsPanel
                liveMetrics={{cpuPercent: 45.2, ramUsedMb: 1024, netInBytes: 1000, netOutBytes: 500}}
                livePlayers={null}
                server={makeServer()}
                node={makeNode()}
            />,
        )
        expect(screen.getAllByText('45.2%').length).toBeGreaterThanOrEqual(1)
    })

    it('renders Status with label', () => {
        render(
            <LiveMetricsPanel
                liveMetrics={null}
                livePlayers={null}
                server={makeServer({status: 'RUNNING'})}
                node={makeNode()}
            />,
        )
        expect(screen.getByText('Running')).toBeInTheDocument()
    })

    it('shows awaiting data when metrics null', () => {
        render(
            <LiveMetricsPanel
                liveMetrics={null}
                livePlayers={null}
                server={makeServer()}
                node={null}
            />,
        )
        expect(screen.getByText('awaiting data')).toBeInTheDocument()
    })
})