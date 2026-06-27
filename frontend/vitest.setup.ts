if (typeof globalThis.PointerEvent === 'undefined') {
    globalThis.PointerEvent = class extends MouseEvent {
        pointerType: string
        constructor(type: string, params?: PointerEventInit) {
            super(type, params)
            this.pointerType = params?.pointerType ?? 'mouse'
        }
    } as any
}

import '@testing-library/jest-dom'

vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
    usePathname: () => '/',
    useParams: () => ({}),
    useSearchParams: () => new URLSearchParams(),
}))
