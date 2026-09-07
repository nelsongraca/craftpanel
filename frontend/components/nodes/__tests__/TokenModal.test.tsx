import {describe, it, expect, vi, beforeEach, afterEach} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {TokenModal} from '../TokenModal'

describe('TokenModal', () => {
    beforeEach(() => {
        vi.stubGlobal('navigator', {
            clipboard: {writeText: vi.fn().mockResolvedValue(undefined)},
        })
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    it('renders title and node key', () => {
        render(<TokenModal nodeKey="abc123def456" onClose={vi.fn()}/>)
        expect(screen.getByText('New Node Key')).toBeInTheDocument()
        expect(screen.getByText('abc123def456')).toBeInTheDocument()
    })

    it('renders warning about old key invalidation', () => {
        render(<TokenModal nodeKey="abc" onClose={vi.fn()}/>)
        expect(screen.getByText(/The old key has been invalidated/)).toBeInTheDocument()
    })

    it('copies key to clipboard and shows Copied', async () => {
        const user = userEvent.setup()
        render(<TokenModal nodeKey="abc123" onClose={vi.fn()}/>)
        await user.click(screen.getByText('Copy'))
        await waitFor(() => {
            expect(screen.getByText('Copied!')).toBeInTheDocument()
        })
    })

    it('calls onClose when Done button clicked', async () => {
        const onClose = vi.fn()
        const user = userEvent.setup()
        render(<TokenModal nodeKey="abc" onClose={onClose}/>)
        await user.click(screen.getByText('Done'))
        expect(onClose).toHaveBeenCalled()
    })
})