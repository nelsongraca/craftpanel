import { describe, it, expect, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useConfirmDialog } from '../useConfirmDialog'

describe('useConfirmDialog', () => {
    it('starts closed', () => {
        const { result } = renderHook(() => useConfirmDialog())
        expect(result.current.dialog.props.open).toBe(false)
    })

    it('confirm() opens the dialog with the given title/description', () => {
        const { result } = renderHook(() => useConfirmDialog())
        act(() => {
            result.current.confirm({ title: 't', description: 'd', onConfirm: vi.fn() })
        })
        expect(result.current.dialog.props.open).toBe(true)
        expect(result.current.dialog.props.title).toBe('t')
        expect(result.current.dialog.props.description).toBe('d')
    })

    it('dialog closing (onOpenChange(false)) closes it', () => {
        const { result } = renderHook(() => useConfirmDialog())
        act(() => {
            result.current.confirm({ title: 't', description: 'd', onConfirm: vi.fn() })
        })
        act(() => {
            result.current.dialog.props.onOpenChange(false)
        })
        expect(result.current.dialog.props.open).toBe(false)
    })

    it('confirm carries destructive and onConfirm through', () => {
        const { result } = renderHook(() => useConfirmDialog())
        const onConfirm = vi.fn()
        act(() => {
            result.current.confirm({ title: 'Delete?', description: 'Sure?', destructive: true, onConfirm })
        })
        expect(result.current.dialog.props.title).toBe('Delete?')
        expect(result.current.dialog.props.description).toBe('Sure?')
        expect(result.current.dialog.props.destructive).toBe(true)
        expect(result.current.dialog.props.onConfirm).toBe(onConfirm)
    })
})
