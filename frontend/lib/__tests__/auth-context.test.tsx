import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import { AuthProvider, useAuth } from '../auth-context'

vi.mock('@/lib/generated', () => ({
    authRefresh: vi.fn(),
    authMe: vi.fn(),
    authLogin: vi.fn(),
    authLogout: vi.fn(),
    authLogoutAll: vi.fn(),
}))

vi.mock('@/lib/client', () => ({
    setAccessToken: vi.fn(),
    getAccessToken: vi.fn(() => null),
    client: { setConfig: vi.fn(), interceptors: { request: { use: vi.fn() }, response: { use: vi.fn() } } },
}))

import * as generated from '@/lib/generated'
import * as clientModule from '@/lib/client'

function TestConsumer() {
    const { user, isLoading } = useAuth()
    return (
        <>
            <div data-testid="loading">{isLoading ? 'loading' : 'ready'}</div>
            <div data-testid="user">{user?.email ?? 'none'}</div>
        </>
    )
}

function TestLogin() {
    const { login } = useAuth()
    return (
        <button onClick={() => login('u@test.com', 'pass')}>login</button>
    )
}

function TestLogout() {
    const { logout } = useAuth()
    return <button onClick={() => logout()}>logout</button>
}

const mockUser = {
    id: '1',
    username: 'user1',
    email: 'u@test.com',
    groups: [],
    permissions: [],
}

describe('AuthProvider', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('restores session when refresh succeeds', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({ data: { access_token: 'tok' } } as never)
        vi.mocked(generated.authMe).mockResolvedValue({ data: mockUser } as never)

        render(<AuthProvider><TestConsumer /></AuthProvider>)

        await waitFor(() => {
            expect(screen.getByTestId('loading')).toHaveTextContent('ready')
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
        expect(clientModule.setAccessToken).toHaveBeenCalledWith('tok')
    })

    it('finishes loading with no user when refresh returns no data', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({ data: undefined } as never)

        render(<AuthProvider><TestConsumer /></AuthProvider>)

        await waitFor(() => {
            expect(screen.getByTestId('loading')).toHaveTextContent('ready')
        })
        expect(screen.getByTestId('user')).toHaveTextContent('none')
    })

    it('finishes loading with no user when refresh throws', async () => {
        vi.mocked(generated.authRefresh).mockRejectedValue(new Error('network'))

        render(<AuthProvider><TestConsumer /></AuthProvider>)

        await waitFor(() => {
            expect(screen.getByTestId('loading')).toHaveTextContent('ready')
        })
        expect(screen.getByTestId('user')).toHaveTextContent('none')
    })

    it('login() sets user and navigates to /', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({ data: undefined } as never)
        vi.mocked(generated.authLogin).mockResolvedValue({ data: { access_token: 'tok2' }, error: undefined } as never)
        vi.mocked(generated.authMe).mockResolvedValue({ data: mockUser } as never)

        render(<AuthProvider><TestConsumer /><TestLogin /></AuthProvider>)
        await waitFor(() => expect(screen.getByTestId('loading')).toHaveTextContent('ready'))

        await act(async () => {
            screen.getByText('login').click()
        })

        await waitFor(() => {
            expect(screen.getByTestId('user')).toHaveTextContent('u@test.com')
        })
        expect(clientModule.setAccessToken).toHaveBeenCalledWith('tok2')
    })

    it('login() throws when API returns error', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({ data: undefined } as never)
        vi.mocked(generated.authLogin).mockResolvedValue({ data: undefined, error: { message: 'Invalid credentials' } } as never)

        let caughtMessage = ''
        function TestLoginError() {
            const { login } = useAuth()
            return (
                <button onClick={async () => {
                    try { await login('x', 'y') } catch (e) { caughtMessage = (e as Error).message }
                }}>login</button>
            )
        }

        render(<AuthProvider><TestLoginError /></AuthProvider>)
        await waitFor(() => {})

        await act(async () => {
            screen.getByText('login').click()
        })

        expect(caughtMessage).toBe('Invalid credentials')
    })

    it('logout() clears user and navigates to /login', async () => {
        vi.mocked(generated.authRefresh).mockResolvedValue({ data: { access_token: 'tok' } } as never)
        vi.mocked(generated.authMe).mockResolvedValue({ data: mockUser } as never)
        vi.mocked(generated.authLogout).mockResolvedValue({} as never)

        render(<AuthProvider><TestConsumer /><TestLogout /></AuthProvider>)
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
        expect(() => render(<Bare />)).toThrow('useAuth must be used within AuthProvider')
    })
})
