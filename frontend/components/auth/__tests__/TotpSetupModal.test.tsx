import {describe, it, expect, vi, beforeEach} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {TotpSetupModal} from '../TotpSetupModal'

vi.mock('@/lib/generated', () => ({
    authTotpSetup: vi.fn(),
    authTotpEnable: vi.fn(),
}))

import * as generated from '@/lib/generated'

const setupData = {
    secret: 'JBSWY3DPEHPK3PXP',
    qr_data_uri: 'data:image/png;base64,AAAA',
    recovery_codes: ['AAAAAAAA', 'BBBBBBBB'],
}

describe('TotpSetupModal', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('shows the QR code and secret after setup', async () => {
        vi.mocked(generated.authTotpSetup).mockResolvedValue({data: setupData, error: undefined} as never)

        render(<TotpSetupModal onClose={vi.fn()} onEnabled={vi.fn()}/>)

        expect(await screen.findByAltText('TOTP QR code')).toBeInTheDocument()
        expect(screen.getByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument()
    })

    it('shows a setup error when the API fails', async () => {
        vi.mocked(generated.authTotpSetup).mockResolvedValue({data: undefined, error: {message: 'TOTP is already enabled'}} as never)

        render(<TotpSetupModal onClose={vi.fn()} onEnabled={vi.fn()}/>)

        expect(await screen.findByText('TOTP is already enabled')).toBeInTheDocument()
    })

    it('shows recovery codes and calls onEnabled after a valid code', async () => {
        const onEnabled = vi.fn()
        vi.mocked(generated.authTotpSetup).mockResolvedValue({data: setupData, error: undefined} as never)
        vi.mocked(generated.authTotpEnable).mockResolvedValue({data: undefined, error: undefined} as never)

        render(<TotpSetupModal onClose={vi.fn()} onEnabled={onEnabled}/>)

        await screen.findByAltText('TOTP QR code')

        const user = userEvent.setup()
        await user.type(screen.getByPlaceholderText('123456'), '123456')
        await user.click(screen.getByRole('button', {name: 'Enable TOTP'}))

        await waitFor(() => {
            expect(generated.authTotpEnable).toHaveBeenCalledWith({body: {code: '123456'}})
            expect(onEnabled).toHaveBeenCalled()
        })
        expect(screen.getByLabelText('Recovery codes')).toHaveValue('AAAAAAAA\nBBBBBBBB')
    })

    it('copies all recovery codes with a single button', async () => {
        vi.mocked(generated.authTotpSetup).mockResolvedValue({data: setupData, error: undefined} as never)
        vi.mocked(generated.authTotpEnable).mockResolvedValue({data: undefined, error: undefined} as never)

        render(<TotpSetupModal onClose={vi.fn()} onEnabled={vi.fn()}/>)

        await screen.findByAltText('TOTP QR code')

        const user = userEvent.setup()
        // userEvent.setup() installs its own clipboard stub, so define ours after it.
        const writeText = vi.fn().mockResolvedValue(undefined)
        Object.defineProperty(navigator, 'clipboard', {value: {writeText}, configurable: true})

        await user.type(screen.getByPlaceholderText('123456'), '123456')
        await user.click(screen.getByRole('button', {name: 'Enable TOTP'}))
        await screen.findByLabelText('Recovery codes')

        fireEvent.click(screen.getByRole('button', {name: 'Copy all'}))

        await waitFor(() => expect(writeText).toHaveBeenCalledWith('AAAAAAAA\nBBBBBBBB'))
    })

    it('keeps the verification form on an invalid code', async () => {
        vi.mocked(generated.authTotpSetup).mockResolvedValue({data: setupData, error: undefined} as never)
        vi.mocked(generated.authTotpEnable).mockResolvedValue({data: undefined, error: {message: 'Invalid verification code'}} as never)

        render(<TotpSetupModal onClose={vi.fn()} onEnabled={vi.fn()}/>)

        await screen.findByAltText('TOTP QR code')

        const user = userEvent.setup()
        await user.type(screen.getByPlaceholderText('123456'), '000000')
        await user.click(screen.getByRole('button', {name: 'Enable TOTP'}))

        await waitFor(() => {
            expect(screen.getByText('Invalid verification code')).toBeInTheDocument()
        })
        expect(screen.queryByLabelText('Recovery codes')).not.toBeInTheDocument()
    })
})
