import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, waitFor} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AccountPage from '../page'

vi.mock('@/lib/generated', () => ({
    authTotpStatus: vi.fn(),
    authTotpSetup: vi.fn(),
    authTotpEnable: vi.fn(),
    authTotpDisable: vi.fn(),
}))

const {changePasswordMock} = vi.hoisted(() => ({changePasswordMock: vi.fn()}))

vi.mock('@/lib/auth-context', () => ({
    useAuth: vi.fn(() => ({changePassword: changePasswordMock})),
}))

import * as generated from '@/lib/generated'
import {useAuth} from '@/lib/auth-context'

function setup(enabled = false) {
    vi.mocked(useAuth).mockReturnValue({
        user: {id: '1', username: 'alice', email: 'alice@test.com', groups: [], permissions: [], server_permissions: {}, totp_enabled: enabled},
        isLoading: false,
        login: vi.fn(),
        verifyTotp: vi.fn(),
        verifyRecovery: vi.fn(),
        logout: vi.fn(),
        logoutAll: vi.fn(),
        changePassword: changePasswordMock,
    } as never)
    return userEvent.setup()
}

async function fillPasswords(oldPw: string, newPw: string, confirmPw: string) {
    const user = userEvent.setup()
    const inputs = document.querySelectorAll<HTMLInputElement>('input[type="password"]')
    expect(inputs.length).toBe(3)
    await user.type(inputs[0], oldPw)
    await user.type(inputs[1], newPw)
    await user.type(inputs[2], confirmPw)
    return user
}

describe('AccountPage', () => {
    beforeEach(() => {
        vi.clearAllMocks()
        changePasswordMock.mockReset()
    })

    it('shows profile info and a disabled TOTP status', async () => {
        setup()
        vi.mocked(generated.authTotpStatus).mockResolvedValue({
            data: {enabled: false, recovery_codes_remaining: 0},
            error: undefined,
        } as never)

        render(<AccountPage/>)

        await waitFor(() => {
            expect(screen.getByText('alice@test.com')).toBeInTheDocument()
            expect(screen.getByText('TOTP is disabled')).toBeInTheDocument()
            expect(screen.getByRole('button', {name: 'Set up TOTP'})).toBeInTheDocument()
        })
    })

    it('shows enabled state with recovery code count', async () => {
        setup(true)
        vi.mocked(generated.authTotpStatus).mockResolvedValue({
            data: {enabled: true, recovery_codes_remaining: 8},
            error: undefined,
        } as never)

        render(<AccountPage/>)

        await waitFor(() => {
            expect(screen.getByText('TOTP is enabled')).toBeInTheDocument()
            expect(screen.getByText('8 recovery codes remaining')).toBeInTheDocument()
            expect(screen.getByRole('button', {name: 'Disable TOTP'})).toBeInTheDocument()
        })
    })

    it('runs the full setup flow: open modal, verify code, shows recovery codes', async () => {
        const user = setup()
        const state = {enabled: false}
        vi.mocked(generated.authTotpStatus).mockImplementation(() => Promise.resolve({
            data: {enabled: state.enabled, recovery_codes_remaining: state.enabled ? 10 : 0},
            error: undefined,
        }) as never)
        vi.mocked(generated.authTotpSetup).mockResolvedValue({
            data: {secret: 'JBSWY3DPEHPK3PXP', qr_data_uri: 'data:image/png;base64,AAAA', recovery_codes: ['AAAAAAAA', 'BBBBBBBB']},
            error: undefined,
        } as never)
        vi.mocked(generated.authTotpEnable).mockImplementation(() => {
            state.enabled = true
            return Promise.resolve({data: undefined, error: undefined}) as never
        })

        render(<AccountPage/>)

        await waitFor(() => {
            expect(screen.getByRole('button', {name: 'Set up TOTP'})).toBeInTheDocument()
        })

        await user.click(screen.getByRole('button', {name: 'Set up TOTP'}))

        expect(await screen.findByAltText('TOTP QR code')).toBeInTheDocument()
        expect(screen.getByText('JBSWY3DPEHPK3PXP')).toBeInTheDocument()

        await user.type(screen.getByPlaceholderText('123456'), '123456')
        await user.click(screen.getByRole('button', {name: 'Enable TOTP'}))

        await waitFor(() => {
            expect(generated.authTotpEnable).toHaveBeenCalledWith({body: {code: '123456'}})
            expect(screen.getByText('AAAAAAAA')).toBeInTheDocument()
            expect(screen.getByText('BBBBBBBB')).toBeInTheDocument()
        })
    })

    it('disables TOTP after confirming with a code', async () => {
        const user = setup(true)
        const state = {enabled: true}
        vi.mocked(generated.authTotpStatus).mockImplementation(() => Promise.resolve({
            data: {enabled: state.enabled, recovery_codes_remaining: state.enabled ? 8 : 0},
            error: undefined,
        }) as never)
        vi.mocked(generated.authTotpDisable).mockImplementation(() => {
            state.enabled = false
            return Promise.resolve({data: undefined, error: undefined}) as never
        })

        render(<AccountPage/>)

        await waitFor(() => {
            expect(screen.getByRole('button', {name: 'Disable TOTP'})).toBeInTheDocument()
        })

        await user.click(screen.getByRole('button', {name: 'Disable TOTP'}))
        await user.type(screen.getByPlaceholderText('123456'), '654321')
        await user.click(screen.getByRole('button', {name: 'Disable 2FA'}))

        await waitFor(() => {
            expect(generated.authTotpDisable).toHaveBeenCalledWith({body: {code: '654321'}})
            expect(screen.getByText('TOTP is disabled')).toBeInTheDocument()
        })
    })

    it('changes the password from the Password section', async () => {
        setup()
        changePasswordMock.mockResolvedValue(undefined)
        vi.mocked(generated.authTotpStatus).mockResolvedValue({
            data: {enabled: false, recovery_codes_remaining: 0},
            error: undefined,
        } as never)

        render(<AccountPage/>)

        const user = await fillPasswords('oldpass', 'newpass1', 'newpass1')
        await user.click(screen.getByRole('button', {name: 'Change Password'}))

        await waitFor(() => {
            expect(changePasswordMock).toHaveBeenCalledWith('oldpass', 'newpass1')
            expect(screen.getByText(/password has been changed/i)).toBeInTheDocument()
        })
    })

    it('does not call the API when the new passwords do not match', async () => {
        setup()
        vi.mocked(generated.authTotpStatus).mockResolvedValue({
            data: {enabled: false, recovery_codes_remaining: 0},
            error: undefined,
        } as never)

        render(<AccountPage/>)

        const user = await fillPasswords('oldpass', 'abc123', 'def456')
        await user.click(screen.getByRole('button', {name: 'Change Password'}))

        expect(screen.getByText('Passwords do not match')).toBeInTheDocument()
        expect(changePasswordMock).not.toHaveBeenCalled()
    })

    it('surfaces the API error when changing the password fails', async () => {
        setup()
        changePasswordMock.mockRejectedValue(new Error('Current password is incorrect'))
        vi.mocked(generated.authTotpStatus).mockResolvedValue({
            data: {enabled: false, recovery_codes_remaining: 0},
            error: undefined,
        } as never)

        render(<AccountPage/>)

        const user = await fillPasswords('wrong', 'newpass1', 'newpass1')
        await user.click(screen.getByRole('button', {name: 'Change Password'}))

        await waitFor(() => {
            expect(screen.getByText('Current password is incorrect')).toBeInTheDocument()
        })
    })
})
