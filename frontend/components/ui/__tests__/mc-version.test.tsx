import {describe, it, expect, vi, beforeEach, afterEach} from 'vitest'
import {render, screen, waitFor, fireEvent} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {McVersionSelect} from '../mc-version'

function manifestResponse(versions: string[]) {
    const res = {
        json: () => Promise.resolve({versions: versions.map((id) => ({id, type: 'release', releaseTime: '2026-01-01T00:00:00+00:00'}))}),
    }
    return Promise.resolve(res)
}

describe('McVersionSelect', () => {
    beforeEach(() => {
        vi.stubGlobal('fetch', vi.fn().mockReturnValue(manifestResponse(['1.21.4', '1.21.3'])))
    })
    afterEach(() => {
        vi.unstubAllGlobals()
    })

    it('renders a skeleton while versions load, then a select with the Mojang release versions', async () => {
        render(<McVersionSelect value="1.21.3" onChange={vi.fn()}/>)
        const user = userEvent.setup()
        const combobox = await screen.findByRole('combobox')
        await user.click(combobox)
        expect(await screen.findByRole('option', {name: '1.21.4'})).toBeTruthy()
        expect(screen.getByRole('option', {name: '1.21.3'})).toBeTruthy()
    })

    it('falls back to a free-text input when the manifest has no releases', async () => {
        vi.mocked(fetch).mockReturnValue(manifestResponse([]))
        render(<McVersionSelect value="" onChange={vi.fn()} placeholder="1.21.4"/>)
        await waitFor(() => expect(screen.getByPlaceholderText('1.21.4')).toBeInTheDocument())
        const input = screen.getByPlaceholderText('1.21.4')
        fireEvent.change(input, {target: {value: '1.20.1'}})
    })

    it('falls back to a free-text input when loading the manifest fails', async () => {
        vi.mocked(fetch).mockRejectedValue(new Error('network down'))
        render(<McVersionSelect value="" onChange={vi.fn()} placeholder="1.21.4"/>)
        await waitFor(() => expect(screen.getByPlaceholderText('1.21.4')).toBeInTheDocument())
    })

    it('notifies onLoaded with the fetched versions', async () => {
        const onLoaded = vi.fn()
        render(<McVersionSelect value="" onChange={vi.fn()} onLoaded={onLoaded}/>)
        await waitFor(() => expect(onLoaded).toHaveBeenCalledWith(['1.21.4', '1.21.3']))
    })

    it('ignores a late manifest response after unmount', async () => {
        let resolve: (value: unknown) => void = () => {
        }
        vi.mocked(fetch).mockReturnValue(new Promise((r) => {
            resolve = r
        }) as never)
        const {unmount} = render(<McVersionSelect value="" onChange={vi.fn()} onLoaded={vi.fn()}/>)
        unmount()
        resolve(manifestResponse(['1.21.4']))
    })
})
