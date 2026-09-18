if (typeof globalThis.PointerEvent === 'undefined') {
    globalThis.PointerEvent = class extends MouseEvent {
        pointerType: string
        constructor(type: string, params?: PointerEventInit) {
            super(type, params)
            this.pointerType = params?.pointerType ?? 'mouse'
        }
    } as unknown as typeof PointerEvent
}

// CodeMirror 6 needs Range.getBoundingClientRect in jsdom
if (typeof Range !== "undefined" && !Range.prototype.getBoundingClientRect) {
    Range.prototype.getBoundingClientRect = function () {
        return new DOMRect();
    };
    Range.prototype.getClientRects = function () {
        return [] as unknown as DOMRectList;
    };
}

import '@testing-library/jest-dom'

// jsdom/vitest Blob interop: stub object URLs so downloads don't throw
URL.createObjectURL = vi.fn(() => 'blob:mock')
URL.revokeObjectURL = vi.fn()

vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
    usePathname: () => '/',
    useParams: () => ({}),
    useSearchParams: () => new URLSearchParams(),
}))
