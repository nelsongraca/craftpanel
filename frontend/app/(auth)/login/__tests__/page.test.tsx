import {describe, it, expect, vi, beforeEach} from 'vitest'
import {render, screen, waitFor, act} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import LoginPage from '../page'
import {useAuth} from '@/lib/auth-context'

vi.mock('@/lib/auth-context', () => ({
    useAuth: vi.fn(),
}))

const mockLogin = vi.fn()
const mockVerifyTotp = vi.fn()
const mockVerifyRecovery = vi.fn()
const mockRouter = {push: vi.fn(), replace: vi.fn()}

vi.mock('next/navigation', () => ({
    useRouter: () => mockRouter,
    usePathname: () => '/login',
    useParams: () => ({}),
}))

const NO_CHALLENGE = {requiresTotp: false, tempToken: null}
const CHALLENGE = {requiresTotp: true, tempToken: 'temp123'}

function setup(overrides: Partial<ReturnType<typeof useAuth>> = {}) {
    vi.mocked(useAuth).mockReturnValue({
        user: null,
        isLoading: false,
        login: mockLogin,
        verifyTotp: mockVerifyTotp,
        verifyRecovery: mockVerifyRecovery,
        logout: vi.fn(),
        logoutAll: vi.fn(),
        changePassword: vi.fn(),
        ...overrides,
    })
    return userEvent.setup()
}

describe('LoginPage', () => {
    beforeEach(() => {
        vi.clearAllMocks()
    })

    it('renders email and password inputs', () => {
        setup()
        render(<LoginPage/>)

        expect(screen.getByPlaceholderText('you@example.com')).toBeInTheDocument()
        expect(screen.getByPlaceholderText('••••••••')).toBeInTheDocument()
        expect(screen.getByRole('button', {name: 'Sign in'})).toBeInTheDocument()
    })

    it('calls login with entered credentials on submit', async () => {
        const user = setup()
        mockLogin.mockResolvedValue(NO_CHALLENGE)

        render(<LoginPage/>)

        await user.type(screen.getByPlaceholderText('you@example.com'), 'admin@test.com')
        await user.type(screen.getByPlaceholderText('••••••••'), 'secret')
        await user.click(screen.getByRole('button', {name: 'Sign in'}))

        expect(mockLogin).toHaveBeenCalledWith('admin@test.com', 'secret')
    })

    it('shows error message on login failure', async () => {
        const user = setup()
        mockLogin.mockRejectedValue(new Error('Invalid credentials'))

        render(<LoginPage/>)

        await user.type(screen.getByPlaceholderText('you@example.com'), 'x@x.com')
        await user.type(screen.getByPlaceholderText('••••••••'), 'wrong')
        await user.click(screen.getByRole('button', {name: 'Sign in'}))

        await waitFor(() => {
            expect(screen.getByText('Invalid credentials')).toBeInTheDocument()
        })
    })

    it('shows TOTP challenge when login requires it', async () => {
        const user = setup()
        mockLogin.mockResolvedValue(CHALLENGE)

        render(<LoginPage/>)

        await user.type(screen.getByPlaceholderText('you@example.com'), 'a@b.com')
        await user.type(screen.getByPlaceholderText('••••••••'), 'pass')
        await user.click(screen.getByRole('button', {name: 'Sign in'}))

        await waitFor(() => {
            expect(screen.getByText('Two-factor authentication')).toBeInTheDocument()
        })
        expect(mockVerifyTotp).not.toHaveBeenCalled()
    })

    it('submits the entered code to verifyTotp', async () => {
        const user = setup()
        mockLogin.mockResolvedValue(CHALLENGE)
        mockVerifyTotp.mockResolvedValue(undefined)

        render(<LoginPage/>)

        await user.type(screen.getByPlaceholderText('you@example.com'), 'a@b.com')
        await user.type(screen.getByPlaceholderText('••••••••'), 'pass')
        await user.click(screen.getByRole('button', {name: 'Sign in'}))

        await waitFor(() => {
            expect(screen.getByText('Two-factor authentication')).toBeInTheDocument()
        })

        await user.type(screen.getByPlaceholderText('123456'), '123456')
        await user.click(screen.getByRole('button', {name: 'Verify'}))

        await waitFor(() => {
            expect(mockVerifyTotp).toHaveBeenCalledWith('temp123', '123456')
        })
    })

    it('switches to recovery code mode and calls verifyRecovery', async () => {
        const user = setup()
        mockLogin.mockResolvedValue(CHALLENGE)
        mockVerifyRecovery.mockResolvedValue(undefined)

        render(<LoginPage/>)

        await user.type(screen.getByPlaceholderText('you@example.com'), 'a@b.com')
        await user.type(screen.getByPlaceholderText('••••••••'), 'pass')
        await user.click(screen.getByRole('button', {name: 'Sign in'}))

        await waitFor(() => {
            expect(screen.getByText('Two-factor authentication')).toBeInTheDocument()
        })

        await user.click(screen.getByRole('button', {name: 'Use a recovery code'}))
        await user.type(screen.getByPlaceholderText('XXXX-XXXX'), 'AAAA-BBBB')
        await user.click(screen.getByRole('button', {name: 'Use recovery code'}))

        await waitFor(() => {
            expect(mockVerifyRecovery).toHaveBeenCalledWith('temp123', 'AAAA-BBBB')
        })
    })

    it('shows verification error message', async () => {
        const user = setup()
        mockLogin.mockResolvedValue(CHALLENGE)
        mockVerifyTotp.mockRejectedValue(new Error('Invalid verification code'))

        render(<LoginPage/>)

        await user.type(screen.getByPlaceholderText('you@example.com'), 'a@b.com')
        await user.type(screen.getByPlaceholderText('••••••••'), 'pass')
        await user.click(screen.getByRole('button', {name: 'Sign in'}))

        await waitFor(() => {
            expect(screen.getByText('Two-factor authentication')).toBeInTheDocument()
        })

        await user.type(screen.getByPlaceholderText('123456'), '000000')
        await user.click(screen.getByRole('button', {name: 'Verify'}))

        await waitFor(() => {
            expect(screen.getByText('Invalid verification code')).toBeInTheDocument()
        })
    })

    it('disables button and shows "Signing in…" while submitting', async () => {
        const user = setup()
        let resolve!: (value: { requiresTotp: boolean; tempToken: string | null }) => void
        mockLogin.mockReturnValue(new Promise<{ requiresTotp: boolean; tempToken: string | null }>((r) => {
            resolve = r
        }))

        render(<LoginPage/>)

        await user.type(screen.getByPlaceholderText('you@example.com'), 'a@b.com')
        await user.type(screen.getByPlaceholderText('••••••••'), 'pass')
        await user.click(screen.getByRole('button'))

        await waitFor(() => {
            expect(screen.getByText('Signing in…')).toBeInTheDocument()
            expect(screen.getByRole('button')).toBeDisabled()
        })

        await act(async () => {
            resolve(NO_CHALLENGE)
        })
    })

    it('redirects to / when already authenticated', async () => {
        setup({user: {id: '1', username: 'u', email: 'u@t.com', groups: [], permissions: [], server_permissions: {}, totp_enabled: false}, isLoading: false})
        render(<LoginPage/>)

        await waitFor(() => {
            expect(mockRouter.replace).toHaveBeenCalledWith('/')
        })
    })

    it('renders nothing while loading', () => {
        setup({isLoading: true})
        const {container} = render(<LoginPage/>)
        expect(container).toBeEmptyDOMElement()
    })
})
