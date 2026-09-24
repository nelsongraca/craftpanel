import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {LiveMetricsCard, LiveMetricsStatCards} from '../live-metrics'
import type {Server, Node} from '@/lib/types'

vi.mock('@/lib/utils/format', () => ({
    fmtMb: (mb: number) => `${(mb / 1024).toFixed(1)} GB`,
    fmtBytes: (b: number) => `${(b / 1024).toFixed(0)} KB`,
    timeAgo: () => '2h ago',
    fmtCpuLimit: (mc: number) => (mc === 0 ? 'Unlimited' : `${mc / 1000} cores`),
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
    ...overrides,
})

const makeNode = (): Node => ({
    id: 'n1',
    display_name: 'Node 1',
    hostname: 'node1.example.com',
    last_seen_at: '2026-09-07T00:00:00Z',
    status: 'ONLINE',
})

describe('LiveMetricsStatCards', () => {
    it('renders Players Online stat card', () => {
        render(
            <LiveMetricsStatCards
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
            <LiveMetricsStatCards
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
            <LiveMetricsStatCards
                liveMetrics={{cpuPercent: 45.2, ramUsedMb: 1024, netInBytes: 1000, netOutBytes: 500}}
                livePlayers={null}
                server={makeServer()}
                node={makeNode()}
            />,
        )
        expect(screen.getByText('45.2%')).toBeInTheDocument()
    })

    it('renders Status with label', () => {
        render(
            <LiveMetricsStatCards
                liveMetrics={null}
                livePlayers={null}
                server={makeServer({status: 'RUNNING'})}
                node={makeNode()}
            />,
        )
        expect(screen.getByText('Running')).toBeInTheDocument()
    })

    it('renders JVM Heap card with used/max when a sample is present', () => {
        render(
            <LiveMetricsStatCards
                liveMetrics={{
                    cpuPercent: 50,
                    ramUsedMb: 1024,
                    netInBytes: 1000,
                    netOutBytes: 500,
                    heapUsedBytes: 2 * 1024 * 1024,
                    heapMaxBytes: 4 * 1024 * 1024,
                    nonHeapUsedBytes: 256 * 1024,
                }}
                livePlayers={null}
                server={makeServer()}
                node={makeNode()}
            />,
        )
        expect(screen.getByText('JVM Heap')).toBeInTheDocument()
        // fmtBytes mock: bytes/1024 KB → "2048 KB / 4096 KB heap"
        expect(screen.getByText(/2048 KB \/ 4096 KB heap/)).toBeInTheDocument()
    })

    it('shows JVM heap unavailable when no sample is present', () => {
        render(
            <LiveMetricsStatCards
                liveMetrics={{cpuPercent: 50, ramUsedMb: 1024, netInBytes: 1000, netOutBytes: 500}}
                livePlayers={null}
                server={makeServer()}
                node={makeNode()}
            />,
        )
        expect(screen.getByText('JVM heap unavailable')).toBeInTheDocument()
    })
})

describe('LiveMetricsCard', () => {
    it('renders CPU and network metrics', () => {
        render(
            <LiveMetricsCard
                liveMetrics={{cpuPercent: 45.2, ramUsedMb: 1024, netInBytes: 2048, netOutBytes: 1024}}
                server={makeServer({memory_mb: 2048})}
            />,
        )
        expect(screen.getByText('Live Metrics')).toBeInTheDocument()
        expect(screen.getByText('CPU')).toBeInTheDocument()
        expect(screen.getByText('Net \u2193')).toBeInTheDocument()
        expect(screen.getByText('Net \u2191')).toBeInTheDocument()
    })

    it('shows awaiting data when metrics are null', () => {
        render(<LiveMetricsCard liveMetrics={null} server={makeServer()}/>)
        expect(screen.getByText('awaiting data\u2026')).toBeInTheDocument()
    })

    it('renders JVM heap and non-heap rows', () => {
        render(
            <LiveMetricsCard
                liveMetrics={{
                    cpuPercent: 45.2,
                    ramUsedMb: 1024,
                    netInBytes: 2048,
                    netOutBytes: 1024,
                    heapUsedBytes: 2 * 1024 * 1024,
                    heapMaxBytes: 4 * 1024 * 1024,
                    nonHeapUsedBytes: 256 * 1024,
                }}
                server={makeServer({memory_mb: 2048})}
            />,
        )
        expect(screen.getByText('JVM Heap')).toBeInTheDocument()
        expect(screen.getByText('JVM Non-Heap')).toBeInTheDocument()
        expect(screen.getByText(/2048 KB \/ 4096 KB/)).toBeInTheDocument()
    })
})
