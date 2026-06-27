import { describe, it, expect, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useAction } from '../useAction'

describe('useAction', () => {
    it('pending starts false, error starts null', () => {
        const fn = vi.fn().mockResolvedValue(undefined)
        const { result } = renderHook(() => useAction(fn))
        expect(result.current[1].pending).toBe(false)
        expect(result.current[1].error).toBeNull()
    })

    it('pending becomes true during execution, false after resolve', async () => {
        let resolve!: () => void
        const promise = new Promise<void>(r => { resolve = r })
        const fn = vi.fn().mockReturnValue(promise)

        const { result } = renderHook(() => useAction(fn))

        act(() => { result.current[0]() })

        expect(result.current[1].pending).toBe(true)

        await act(async () => { resolve() })

        expect(result.current[1].pending).toBe(false)
    })

    it('error is null on success', async () => {
        const fn = vi.fn().mockResolvedValue(undefined)
        const { result } = renderHook(() => useAction(fn))

        await act(async () => { await result.current[0]() })

        expect(result.current[1].error).toBeNull()
    })

    it('error captures thrown message via e.message', async () => {
        const fn = vi.fn().mockRejectedValue(new Error('boom'))
        const { result } = renderHook(() => useAction(fn))

        await act(async () => { await result.current[0]() })

        expect(result.current[1].error).toBe('boom')
    })

    it('error falls back to Request failed when thrown value is not an Error', async () => {
        const fn = vi.fn().mockRejectedValue('string error')
        const { result } = renderHook(() => useAction(fn))

        await act(async () => { await result.current[0]() })

        expect(result.current[1].error).toBe('Request failed')
    })
})
