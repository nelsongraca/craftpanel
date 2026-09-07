import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {InfoRow} from '../server-info'

describe('InfoRow', () => {
    it('renders label and value', () => {
        render(<InfoRow label="Type" value="PAPER"/>)
        expect(screen.getByText('Type')).toBeInTheDocument()
        expect(screen.getByText('PAPER')).toBeInTheDocument()
    })

    it('renders numeric value', () => {
        render(<InfoRow label="Port" value={25565}/>)
        expect(screen.getByText('25565')).toBeInTheDocument()
    })
})