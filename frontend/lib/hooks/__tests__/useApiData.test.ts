import {act, renderHook, waitFor} from '@testing-library/react'
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {useApiData} from '../useApiData'

describe('useApiData', () => {
    it('sets data and clears loading after successful load', async () => {
        const loader = vi.fn().mockResolvedValue({name: 'test'})
        const {result} = renderHook(() => useApiData(loader, []))
        expect(result.current.loading).toBe(true)
        await waitFor(() => expect(result.current.loading).toBe(false))
        expect(result.current.data).toEqual({name: 'test'})
        expect(result.current.error).toBeNull()
    })

    it('sets error string on loader failure', async () => {
        const loader = vi.fn().mockRejectedValue(new Error('fetch failed'))
        const {result} = renderHook(() => useApiData(loader, []))
        await waitFor(() => expect(result.current.loading).toBe(false))
        expect(result.current.error).toBe('fetch failed')
        expect(result.current.data).toBeUndefined()
    })

    it('calls loader again when reload is invoked', async () => {
        const loader = vi.fn().mockResolvedValue('first')
        const {result} = renderHook(() => useApiData(loader, []))
        await waitFor(() => expect(result.current.loading).toBe(false))
        expect(loader).toHaveBeenCalledTimes(1)

        loader.mockResolvedValue('second')
        act(() => {
            result.current.reload()
        })
        await waitFor(() => expect(result.current.data).toBe('second'))
        expect(loader).toHaveBeenCalledTimes(2)
    })

    describe('timer-based', () => {
        beforeEach(() => {
            vi.useFakeTimers()
        })

        afterEach(() => {
            vi.useRealTimers()
        })

        it('polls at pollMs interval', async () => {
            const loader = vi.fn().mockResolvedValue('data')
            renderHook(() => useApiData(loader, [], {pollMs: 30_000}))
            await act(() => Promise.resolve())
            expect(loader).toHaveBeenCalledTimes(1)

            await act(() => vi.advanceTimersByTimeAsync(30_000))
            expect(loader).toHaveBeenCalledTimes(2)

            await act(() => vi.advanceTimersByTimeAsync(30_000))
            expect(loader).toHaveBeenCalledTimes(3)
        })

        it('clears interval on unmount', async () => {
            const loader = vi.fn().mockResolvedValue('data')
            const {unmount} = renderHook(() => useApiData(loader, [], {pollMs: 30_000}))
            await act(() => Promise.resolve())
            expect(loader).toHaveBeenCalledTimes(1)

            unmount()
            await act(() => vi.advanceTimersByTimeAsync(60_000))
            expect(loader).toHaveBeenCalledTimes(1)
        })
    })
})
