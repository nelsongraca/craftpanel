import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {StatCard, RamBarInline} from '../stat-cards'

describe('StatCard', () => {
    it('renders label and children', () => {
        render(<StatCard label="RAM"><span>2048 MB</span></StatCard>)
        expect(screen.getByText('RAM')).toBeInTheDocument()
        expect(screen.getByText('2048 MB')).toBeInTheDocument()
    })
})

describe('RamBarInline', () => {
    it('renders dash when usedMb is null', () => {
        render(<RamBarInline usedMb={null} totalMb={2048}/>)
        expect(screen.getByText('-')).toBeInTheDocument()
        expect(screen.getByText('- / 2.0 GB alloc')).toBeInTheDocument()
    })

    it('renders used and total memory', () => {
        render(<RamBarInline usedMb={1024} totalMb={2048}/>)
        expect(screen.getByText('1.0 GB')).toBeInTheDocument()
    })

    it('applies green bar when usage is low', () => {
        const {container} = render(<RamBarInline usedMb={512} totalMb={2048}/>)
        const bar = container.querySelector('.bg-accent')
        expect(bar).toBeInTheDocument()
    })

    it('applies warning bar when usage is moderate', () => {
        const {container} = render(<RamBarInline usedMb={1500} totalMb={2048}/>)
        const bar = container.querySelector('.bg-warning')
        expect(bar).toBeInTheDocument()
    })

    it('applies error bar when usage is high', () => {
        const {container} = render(<RamBarInline usedMb={1800} totalMb={2048}/>)
        const bar = container.querySelector('.bg-error')
        expect(bar).toBeInTheDocument()
    })
})