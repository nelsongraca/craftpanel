import {describe, it, expect, vi, beforeEach, afterEach} from 'vitest'
import {render, screen} from '@testing-library/react'
import type {ReactNode} from 'react'

vi.mock('next/font/google', () => ({
    Barlow: () => ({variable: '--font-sans'}),
    Barlow_Condensed: () => ({variable: '--font-condensed'}),
    JetBrains_Mono: () => ({variable: '--font-mono'}),
}))

vi.mock('@/lib/auth-context', () => ({
    AuthProvider: ({children}: { children: ReactNode }) => (
        <div data-testid="auth-provider">{children}</div>
    ),
}))

import RootLayout from '../layout'

// RootLayout renders <html>; jsdom's render container is a <div>, which React flags.
beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => {
    })
})

afterEach(() => {
    vi.restoreAllMocks()
})

describe('RootLayout', () => {
    it('renders children inside the AuthProvider', () => {
        render(
            <RootLayout>
                <main data-testid="content">Hello</main>
            </RootLayout>,
        )
        expect(screen.getByTestId('auth-provider')).toBeInTheDocument()
        expect(screen.getByTestId('content')).toHaveTextContent('Hello')
    })

    it('wraps the document in an html element with the dark class', () => {
        render(<RootLayout><span/></RootLayout>)
        const html = document.documentElement
        expect(html.lang).toBe('en')
        expect(html.classList).toContain('dark')
        expect(html.classList).toContain('antialiased')
    })

    it('sets the API origin script from PUBLIC_API_URL', () => {
        vi.stubEnv('PUBLIC_API_URL', 'https://api.example.com')
        render(<RootLayout><span/></RootLayout>)
        expect(document.documentElement.outerHTML).toContain(
            'window.__API_URL__="https://api.example.com"',
        )
        vi.unstubAllEnvs()
    })

    it('defaults API origin to empty string', () => {
        vi.stubEnv('PUBLIC_API_URL', '')
        render(<RootLayout><span/></RootLayout>)
        expect(document.documentElement.outerHTML).toContain('window.__API_URL__=""')
        vi.unstubAllEnvs()
    })
})
