import { describe, it, expect, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useConfirmDialog } from '../useConfirmDialog'

describe('useConfirmDialog', () => {
    it('initial state is null', () => {
        const { result } = renderHook(() => useConfirmDialog())
        expect(result.current.state).toBeNull()
    })

    it('confirm() sets the state', () => {
        const { result } = renderHook(() => useConfirmDialog())
        act(() => {
            result.current.confirm({ title: 't', description: 'd', onConfirm: vi.fn() })
        })
        expect(result.current.state).toEqual({ title: 't', description: 'd', onConfirm: expect.any(Function) })
    })

    it('dialog closing (onOpenChange(false)) clears the state', () => {
        const { result } = renderHook(() => useConfirmDialog())
        act(() => {
            result.current.confirm({ title: 't', description: 'd', onConfirm: vi.fn() })
        })
        act(() => {
            result.current.dialog.props.onOpenChange(false)
        })
        expect(result.current.state).toBeNull()
    })

    it('confirm updates title/description/destructive/onConfirm correctly', () => {
        const { result } = renderHook(() => useConfirmDialog())
        const onConfirm = vi.fn()
        act(() => {
            result.current.confirm({ title: 'Delete?', description: 'Sure?', destructive: true, onConfirm })
        })
        expect(result.current.state).toEqual({
            title: 'Delete?',
            description: 'Sure?',
            destructive: true,
            onConfirm,
        })
    })
})
