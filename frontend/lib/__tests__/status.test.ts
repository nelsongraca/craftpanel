import {describe, it, expect} from 'vitest'
import {
    serverStatusLabel,
    serverStatusClass,
    nodeDisplayStatus,
    nodeStatusLabel,
    nodeStatusClass,
} from '../status'

describe('serverStatusLabel', () => {
    it('returns Healthy for HEALTHY', () => {
        expect(serverStatusLabel('HEALTHY')).toBe('Healthy')
    })

    it('returns Unhealthy for UNHEALTHY', () => {
        expect(serverStatusLabel('UNHEALTHY')).toBe('Unhealthy')
    })

    it('returns Starting for STARTING', () => {
        expect(serverStatusLabel('STARTING')).toBe('Starting')
    })

    it('returns Stopping for STOPPING', () => {
        expect(serverStatusLabel('STOPPING')).toBe('Stopping')
    })

    it('returns Stopped for STOPPED', () => {
        expect(serverStatusLabel('STOPPED')).toBe('Stopped')
    })

    it('falls back to Stopped for unknown status', () => {
        expect(serverStatusLabel('BOGUS')).toBe('Stopped')
    })
})

describe('serverStatusClass', () => {
    it('returns healthy CSS for HEALTHY', () => {
        expect(serverStatusClass('HEALTHY')).toBe('text-healthy  border border-healthy/30  bg-healthy/10')
    })

    it('returns error CSS for UNHEALTHY', () => {
        expect(serverStatusClass('UNHEALTHY')).toBe('text-error    border border-error/30    bg-error/10')
    })

    it('returns warning CSS for STARTING', () => {
        expect(serverStatusClass('STARTING')).toBe('text-warning  border border-warning/30  bg-warning/10')
    })

    it('returns warning CSS for STOPPING', () => {
        expect(serverStatusClass('STOPPING')).toBe('text-warning  border border-warning/30  bg-warning/10')
    })

    it('returns muted CSS for STOPPED', () => {
        expect(serverStatusClass('STOPPED')).toBe('text-text-muted border border-border    bg-surface-high')
    })

    it('falls back to STOPPED CSS for unknown status', () => {
        expect(serverStatusClass('BOGUS')).toBe('text-text-muted border border-border    bg-surface-high')
    })
})

describe('nodeDisplayStatus', () => {
    it('returns DEGRADED for ACTIVE + DEGRADED health', () => {
        expect(nodeDisplayStatus('ACTIVE', 'DEGRADED')).toBe('DEGRADED')
    })

    it('returns UNREACHABLE for ACTIVE + UNREACHABLE health', () => {
        expect(nodeDisplayStatus('ACTIVE', 'UNREACHABLE')).toBe('UNREACHABLE')
    })

    it('returns ACTIVE for ACTIVE + healthy health', () => {
        expect(nodeDisplayStatus('ACTIVE', 'HEALTHY')).toBe('ACTIVE')
    })

    it('returns ACTIVE for ACTIVE without health', () => {
        expect(nodeDisplayStatus('ACTIVE')).toBe('ACTIVE')
    })

    it('returns PENDING for PENDING lifecycle', () => {
        expect(nodeDisplayStatus('PENDING')).toBe('PENDING')
    })

    it('returns REJECTED for REJECTED lifecycle', () => {
        expect(nodeDisplayStatus('REJECTED')).toBe('REJECTED')
    })

    it('returns DECOMMISSIONED for DECOMMISSIONED lifecycle', () => {
        expect(nodeDisplayStatus('DECOMMISSIONED')).toBe('DECOMMISSIONED')
    })

    it('falls back to PENDING for unknown lifecycle', () => {
        expect(nodeDisplayStatus('BOGUS')).toBe('PENDING')
    })
})

describe('nodeStatusLabel', () => {
    it('returns Active for ACTIVE', () => {
        expect(nodeStatusLabel('ACTIVE', 'HEALTHY')).toBe('Active')
    })

    it('returns Degraded for ACTIVE + DEGRADED', () => {
        expect(nodeStatusLabel('ACTIVE', 'DEGRADED')).toBe('Degraded')
    })

    it('returns Unreachable for ACTIVE + UNREACHABLE', () => {
        expect(nodeStatusLabel('ACTIVE', 'UNREACHABLE')).toBe('Unreachable')
    })

    it('returns Pending for PENDING lifecycle', () => {
        expect(nodeStatusLabel('PENDING')).toBe('Pending')
    })

    it('returns Rejected for REJECTED lifecycle', () => {
        expect(nodeStatusLabel('REJECTED')).toBe('Rejected')
    })

    it('returns Decommissioned for DECOMMISSIONED lifecycle', () => {
        expect(nodeStatusLabel('DECOMMISSIONED')).toBe('Decommissioned')
    })

    it('returns Pending as fallback for unknown status', () => {
        expect(nodeStatusLabel('BOGUS')).toBe('Pending')
    })
})

describe('nodeStatusClass', () => {
    it('returns healthy CSS for ACTIVE', () => {
        expect(nodeStatusClass('ACTIVE', 'HEALTHY')).toBe('text-healthy  border border-healthy/30  bg-healthy/10')
    })

    it('returns warning CSS for PENDING', () => {
        expect(nodeStatusClass('PENDING')).toBe('text-warning  border border-warning/30  bg-warning/10')
    })

    it('returns warning CSS for DEGRADED', () => {
        expect(nodeStatusClass('ACTIVE', 'DEGRADED')).toBe('text-warning  border border-warning/30  bg-warning/10')
    })

    it('returns error CSS for UNREACHABLE', () => {
        expect(nodeStatusClass('ACTIVE', 'UNREACHABLE')).toBe('text-error    border border-error/30    bg-error/10')
    })

    it('returns muted CSS for REJECTED', () => {
        expect(nodeStatusClass('REJECTED')).toBe('text-text-muted border border-border   bg-surface-high')
    })

    it('returns muted CSS for DECOMMISSIONED', () => {
        expect(nodeStatusClass('DECOMMISSIONED')).toBe('text-text-muted border border-border   bg-surface-high')
    })

    it('returns warning CSS as fallback for unknown status', () => {
        expect(nodeStatusClass('BOGUS')).toBe('text-warning  border border-warning/30  bg-warning/10')
    })
})
