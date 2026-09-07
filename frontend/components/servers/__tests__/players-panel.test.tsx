import {describe, it, expect} from 'vitest'
import {render, screen} from '@testing-library/react'
import {PlayersPanel} from '../players-panel'

describe('PlayersPanel', () => {
    it('renders nothing when livePlayers is null', () => {
        const {container} = render(<PlayersPanel livePlayers={null}/>)
        expect(container.innerHTML).toBe('')
    })

    it('renders nothing when count is 0', () => {
        const {container} = render(<PlayersPanel livePlayers={{count: 0, list: []}}/>)
        expect(container.innerHTML).toBe('')
    })

    it('renders player count and names', () => {
        render(<PlayersPanel livePlayers={{count: 2, list: ['Steve', 'Alex']}}/>)
        expect(screen.getByText('Online Players (2)')).toBeInTheDocument()
        expect(screen.getByText('Steve')).toBeInTheDocument()
        expect(screen.getByText('Alex')).toBeInTheDocument()
    })
})