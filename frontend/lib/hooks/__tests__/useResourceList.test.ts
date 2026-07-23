import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useResourceList } from '../useResourceList'

describe('useResourceList', () => {
    beforeEach(() => {
        vi.useFakeTimers()
    })

    afterEach(() => {
        vi.useRealTimers()
    })

    it('initialLoad starts true, becomes false after first load, data populated from loader', async () => {
        const loader = vi.fn().mockResolvedValue({ data: [1, 2, 3] })
        const { result } = renderHook(() => useResourceList(loader, []))

        expect(result.current.initialLoad).toBe(true)
        expect(result.current.data).toEqual([])

        await act(async () => {
            await vi.runOnlyPendingTimersAsync()
        })

        expect(result.current.initialLoad).toBe(false)
        expect(result.current.data).toEqual([1, 2, 3])
    })

    it('loader returning {} leaves data as [] and does not throw', async () => {
        const loader = vi.fn().mockResolvedValue({})
        const { result } = renderHook(() => useResourceList(loader, []))

        await act(async () => {
            await vi.runOnlyPendingTimersAsync()
        })

        expect(result.current.initialLoad).toBe(false)
        expect(result.current.data).toEqual([])
    })

    it('reload() re-fetches and updates data', async () => {
        const loader = vi.fn()
            .mockResolvedValueOnce({ data: [1] })
            .mockResolvedValueOnce({ data: [1, 2] })
        const { result } = renderHook(() => useResourceList(loader, []))

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0)
        })
        expect(result.current.data).toEqual([1])

        await act(async () => {
            result.current.reload()
            await vi.advanceTimersByTimeAsync(0)
        })

        expect(result.current.data).toEqual([1, 2])
    })

    it('polling: advancing pollMs triggers loader again', async () => {
        const loader = vi.fn().mockResolvedValue({ data: [] })
        renderHook(() => useResourceList(loader, [], { pollMs: 5_000 }))

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0)
        })
        expect(loader).toHaveBeenCalledTimes(1)

        await act(async () => {
            await vi.advanceTimersByTimeAsync(5_000)
        })
        expect(loader).toHaveBeenCalledTimes(2)

        await act(async () => {
            await vi.advanceTimersByTimeAsync(5_000)
        })
        expect(loader).toHaveBeenCalledTimes(3)
    })

    it('unmount clears the interval so loader is not called again', async () => {
        const loader = vi.fn().mockResolvedValue({ data: [] })
        const { unmount } = renderHook(() => useResourceList(loader, [], { pollMs: 5_000 }))

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0)
        })
        expect(loader).toHaveBeenCalledTimes(1)

        unmount()

        await act(async () => {
            await vi.advanceTimersByTimeAsync(20_000)
        })
        expect(loader).toHaveBeenCalledTimes(1)
    })

    it('pollMs: 0 loads once and never polls', async () => {
        const loader = vi.fn().mockResolvedValue({ data: [] })
        renderHook(() => useResourceList(loader, [], { pollMs: 0 }))

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0)
        })
        expect(loader).toHaveBeenCalledTimes(1)

        await act(async () => {
            await vi.advanceTimersByTimeAsync(60_000)
        })
        expect(loader).toHaveBeenCalledTimes(1)
    })

    it('setData patches state directly (WS seam)', async () => {
        const loader = vi.fn().mockResolvedValue({ data: [{ id: '1', health: 'HEALTHY' }] })
        const { result } = renderHook(() => useResourceList(loader, []))

        await act(async () => {
            await vi.runOnlyPendingTimersAsync()
        })

        act(() => {
            result.current.setData((prev) =>
                prev.map((item) => (item.id === '1' ? { ...item, health: 'UNHEALTHY' } : item)),
            )
        })

        expect(result.current.data).toEqual([{ id: '1', health: 'UNHEALTHY' }])
    })

    it('stable deps across re-render do not re-trigger the polling effect', async () => {
        const loader = vi.fn().mockResolvedValue({ data: [] })
        const { rerender } = renderHook(
            ({ id }) => useResourceList(loader, [id], { pollMs: 5_000 }),
            { initialProps: { id: 'a' } },
        )

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0)
        })
        expect(loader).toHaveBeenCalledTimes(1)

        rerender({ id: 'a' })
        expect(loader).toHaveBeenCalledTimes(1)
    })

    it('changed deps re-trigger a load', async () => {
        const loader = vi.fn().mockResolvedValue({ data: [] })
        const { rerender } = renderHook(
            ({ id }) => useResourceList(loader, [id], { pollMs: 5_000 }),
            { initialProps: { id: 'a' } },
        )

        await act(async () => {
            await vi.advanceTimersByTimeAsync(0)
        })
        expect(loader).toHaveBeenCalledTimes(1)

        await act(async () => {
            rerender({ id: 'b' })
            await vi.advanceTimersByTimeAsync(0)
        })
        expect(loader).toHaveBeenCalledTimes(2)
    })
})
