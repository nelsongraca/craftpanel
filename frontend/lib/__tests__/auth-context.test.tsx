import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, waitFor, act} from '@testing-library/react'
import {AuthProvider, useAuth, type LoginOutcome} from '../auth-context'

vi.mock('@/lib/generated', () => ({
    authRefresh: vi.fn(),
    authMe: vi.fn(),
    authLogin: vi.fn(),
    authLogout: vi.fn(),
    authLogoutAll: vi.fn(),
    authChangePassword: vi.fn(),
    authTotpVerify: vi.fn(),
    authTotpRecovery: vi.fn(),
}))

vi.mock('@/lib/client', () => ({
    setAccessToken: vi.fn(),
    getAccessToken: vi.fn(() => null),
    client: {setConfig: vi.fn(), interceptors: {request: {use: vi.fn()}, response: {use: vi.fn()}}},
}))

vi.mock('@/lib/fingerprint', () => ({
    initFingerprint: vi.fn(),
    getCachedFingerprint: vi.fn(() => null),
}))

import * as generated from '@/lib/generated'
import * as clientModule from '@/lib/client'

function TestConsumer() {
    const {user, isLoading} = useAuth()
    return (
        <>
            <div data-testid="loading">{isLoading ? 'loading' : 'ready'}</div>
            <div data-testid="user">{user?.email ?? 'none'}</div>
        </>
    )
}

function TestLogin() {
    const {login} = useAuth()
    return (
        <button onClick={() => login('u@test.com', 'pass')}>login</button>
    )
}

function TestLogout() {
    const {logout} = useAuth()
    return <button onClick={() => logout()}>logout</button>
}

function TestLogoutAll() {
    const {logoutAll} = useAuth()
    return <button onClick={() => logoutAll()}>logout all</button>
}

function TestChangePassword() {
    const {changePassword} = useAuth()
    return <button onClick={() => changePassword('old', 'new')}>change pw</button>
}

function TestForceChangePassword() {
    const {forceChangePassword} = useAuth()
    return <button onClick={() => forceChangePassword('new')}>force change pw</button>
}

const mockUser = {
    id: '1',
    username: 'user1',
    email: 'u@test.com',
    groups: [],
    permissions: [],
    totp_enabled: false,
}

describe('AuthProvider', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('restores session when refresh succeeds', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: {access_token: 'tok'}} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)

        render(<AuthProvider><TestConsumer/></AuthProvider>)

        await waitFor(() => {
            expect(screen.getByTestId('loading')).toHaveTextContent('ready')
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
        expect(clientModule.setAccessToken).toHaveBeenCalledWith('tok')
    })

    it('finishes loading with no user when refresh returns no data', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: undefined} as never)

        render(<AuthProvider><TestConsumer/></AuthProvider>)

        await waitFor(() => {
            expect(screen.getByTestId('loading')).toHaveTextContent('ready')
        })
        expect(screen.getByTestId('user')).toHaveTextContent('none')
    })

    it('finishes loading with no user when refresh throws', async () => {
        vi.mocked(generated.authRefresh).mockRejectedValue(new Error('network'))

        render(<AuthProvider><TestConsumer/></AuthProvider>)

        await waitFor(() => {
            expect(screen.getByTestId('loading')).toHaveTextContent('ready')
        })
        expect(screen.getByTestId('user')).toHaveTextContent('none')
    })

    it('login() sets user and navigates to /', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: undefined} as never)
        vi.mocked(generated.authLogin).mockResolvedValue({data: {access_token: 'tok2'}, error: undefined} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)

        render(<AuthProvider><TestConsumer/><TestLogin/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('ready'))

        await act(async () => {
            screen.getByText('login').click()
        })

        await waitFor(() => {
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
        expect(clientModule.setAccessToken).toHaveBeenCalledWith('tok2')
    })

    it('login() returns a totp challenge without authenticating when 2FA is required', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: undefined} as never)
        vi.mocked(generated.authLogin).mockResolvedValue({
            data: {requires_totp: true, temp_token: 'temp123', expires_in: 60},
            error: undefined,
        } as never)

        let outcome: LoginOutcome | null = null

        function TestLoginChallenge() {
            const {login} = useAuth()
            return (
                <button onClick={async () => {
                    try {
                        outcome = await login('u@test.com', 'pass')
                    } catch {
                        outcome = null
                    }
                }}>login</button>
            )
        }

        render(<AuthProvider><TestConsumer/><TestLoginChallenge/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('ready'))

        await act(async () => {
            screen.getByText('login').click()
        })

        await waitFor(() => expect(outcome).toEqual({requiresTotp: true, tempToken: 'temp123'}))
        expect(clientModule.setAccessToken).not.toHaveBeenCalled()
        expect(generated.authMe).not.toHaveBeenCalled()
        expect(screen.getByTestId('user')).toHaveTextContent('none')
    })

    it('verifyTotp completes login after a totp challenge', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: undefined} as never)
        vi.mocked(generated.authTotpVerify).mockResolvedValue({data: {access_token: 'tok3'}, error: undefined} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)

        function TestVerify() {
            const {verifyTotp} = useAuth()
            return <button onClick={() => verifyTotp('temp123', '123456')}>verify</button>
        }

        render(<AuthProvider><TestConsumer/><TestVerify/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('ready'))

        await act(async () => {
            screen.getByText('verify').click()
        })

        await waitFor(() => {
            expect(generated.authTotpVerify).toHaveBeenCalledWith({
                body: {temp_token: 'temp123', code: '123456', trust_device: false, device_fingerprint: undefined}
            })
            expect(clientModule.setAccessToken).toHaveBeenCalledWith('tok3')
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
    })

    it('verifyTotp throws when the API returns an error', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: undefined} as never)
        vi.mocked(generated.authTotpVerify).mockResolvedValue({
            data: undefined,
            error: {message: 'Invalid verification code'},
        } as never)

        let caughtMessage = ''

        function TestVerifyError() {
            const {verifyTotp} = useAuth()
            return (
                <button onClick={async () => {
                    try {
                        await verifyTotp('temp123', '000000')
                    } catch (e) {
                        caughtMessage = (e as Error).message
                    }
                }}>verify</button>
            )
        }

        render(<AuthProvider><TestVerifyError/></AuthProvider>)
        await waitFor(() => {
        })

        await act(async () => {
            screen.getByText('verify').click()
        })

        expect(caughtMessage).toBe('Invalid verification code')
    })

    it('verifyRecovery completes login using a recovery code', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: undefined} as never)
        vi.mocked(generated.authTotpRecovery).mockResolvedValue({data: {access_token: 'tok4'}, error: undefined} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)

        function TestRecovery() {
            const {verifyRecovery} = useAuth()
            return <button onClick={() => verifyRecovery('temp123', 'ABCD-EFGH')}>recover</button>
        }

        render(<AuthProvider><TestConsumer/><TestRecovery/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('ready'))

        await act(async () => {
            screen.getByText('recover').click()
        })

        await waitFor(() => {
            expect(generated.authTotpRecovery).toHaveBeenCalledWith({body: {temp_token: 'temp123', code: 'ABCD-EFGH'}})
            expect(clientModule.setAccessToken).toHaveBeenCalledWith('tok4')
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
    })

    it('login() throws when API returns error', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: undefined} as never)
        vi.mocked(generated.authLogin).mockResolvedValue({data: undefined, error: {message: 'Invalid credentials'}} as never)

        let caughtMessage = ''

        function TestLoginError() {
            const {login} = useAuth()
            return (
                <button onClick={async () => {
                    try {
                        await login('x', 'y')
                    } catch (e) {
                        caughtMessage = (e as Error).message
                    }
                }}>login</button>
            )
        }

        render(<AuthProvider><TestLoginError/></AuthProvider>)
        await waitFor(() => {
        })

        await act(async () => {
            screen.getByText('login').click()
        })

        expect(caughtMessage).toBe('Invalid credentials')
    })

    it('logout() clears user and navigates to /login', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: {access_token: 'tok'}} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)
        vi.mocked(generated.authLogout).mockResolvedValue({} as never)

        render(<AuthProvider><TestConsumer/><TestLogout/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('u@test.com'))

        await act(async () => {
            screen.getByText('logout').click()
        })

        await waitFor(() => {
            expect(screen.getByTestId('user')).toHaveTextContent('none')
        })
        expect(clientModule.setAccessToken).toHaveBeenCalledWith(null)
    })

    it('useAuth() throws outside AuthProvider', () => {
        function Bare() {
            useAuth()
            return null
        }

        expect(() => render(<Bare/>)).toThrow('useAuth must be used within AuthProvider')
    })

    it('logoutAll calls authLogoutAll and returns true on success', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: {access_token: 'tok'}} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)
        vi.mocked(generated.authLogoutAll).mockResolvedValue({data: undefined, error: undefined} as never)

        render(<AuthProvider><TestConsumer/><TestLogoutAll/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('u@test.com'))

        await act(async () => {
            screen.getByText('logout all').click()
        })

        await waitFor(() => {
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
        expect(generated.authLogoutAll).toHaveBeenCalled()
    })

    it('logoutAll returns false on failure without clearing state', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: {access_token: 'tok'}} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)
        vi.mocked(generated.authLogoutAll).mockResolvedValue({data: undefined, error: {message: 'fail'}} as never)

        render(<AuthProvider><TestConsumer/><TestLogoutAll/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('u@test.com'))

        await act(async () => {
            screen.getByText('logout all').click()
        })

        await waitFor(() => {
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
        expect(clientModule.setAccessToken).not.toHaveBeenCalledWith(null)
    })

    it('changePassword calls API and refreshes user', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: {access_token: 'tok'}} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)
        vi.mocked(generated.authChangePassword).mockResolvedValue({data: undefined, error: undefined} as never)

        render(<AuthProvider><TestConsumer/><TestChangePassword/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('u@test.com'))

        await act(async () => {
            screen.getByText('change pw').click()
        })

        await waitFor(() => {
            expect(generated.authChangePassword).toHaveBeenCalledWith({body: {old_password: 'old', new_password: 'new'}})
        })
        await waitFor(() => {
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
    })

    it('changePassword throws when API returns error', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: {access_token: 'tok'}} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)
        vi.mocked(generated.authChangePassword).mockResolvedValue({data: undefined, error: {message: 'Current password is incorrect'}} as never)

        let caughtMessage = ''

        function TestChangePwError() {
            const {changePassword} = useAuth()
            return (
                <button onClick={async () => {
                    try {
                        await changePassword('wrong', 'new')
                    } catch (e) {
                        caughtMessage = (e as Error).message
                    }
                }}>change pw</button>
            )
        }

        render(<AuthProvider><TestChangePwError/></AuthProvider>)
        await waitFor(() => {
        })

        await act(async () => {
            screen.getByText('change pw').click()
        })

        expect(caughtMessage).toBe('Current password is incorrect')
    })

    it('forceChangePassword calls API with empty old password and refreshes user', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: {access_token: 'tok'}} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)
        vi.mocked(generated.authChangePassword).mockResolvedValue({data: undefined, error: undefined} as never)

        render(<AuthProvider><TestConsumer/><TestForceChangePassword/></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('user')).toHaveTextContent('u@test.com'))

        await act(async () => {
            screen.getByText('force change pw').click()
        })

        await waitFor(() => {
            expect(generated.authChangePassword).toHaveBeenCalledWith({body: {old_password: '', new_password: 'new'}})
        })
        await waitFor(() => {
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
    })

    it('forceChangePassword throws when API returns error', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({data: {access_token: 'tok'}} as never)
        vi.mocked(generated.authMe).mockResolvedValue({data: mockUser} as never)
        vi.mocked(generated.authChangePassword).mockResolvedValue({data: undefined, error: {message: 'Failed to change password'}} as never)

        let caughtMessage = ''

        function TestForceChangePwError() {
            const {forceChangePassword} = useAuth()
            return (
                <button onClick={async () => {
                    try {
                        await forceChangePassword('new')
                    } catch (e) {
                        caughtMessage = (e as Error).message
                    }
                }}>force change pw error</button>
            )
        }

        render(<AuthProvider><TestForceChangePwError/></AuthProvider>)
        await waitFor(() => {
        })

        await act(async () => {
            screen.getByText('force change pw error').click()
        })

        expect(caughtMessage).toBe('Failed to change password')
    })
})
