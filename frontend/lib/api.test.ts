import {describe, expect, it} from 'vitest'
import {ApiError, call, tryCall} from './api'

const mockResponse = (status: number) => ({status} as Response)

describe('call', () => {
    it('returns data on success', async () => {
        const data = await call(() => Promise.resolve({data: {id: '1'}}))
        expect(data).toEqual({id: '1'})
    })

    it('throws ApiError with status on SDK error', async () => {
        await expect(
            call(() =>
                Promise.resolve({
                    error: {message: 'Not found'},
                    response: mockResponse(404),
                }),
            ),
        ).rejects.toMatchObject({status: 404, message: 'Not found'})
    })

    it('throws ApiError with fallback message when error.message missing', async () => {
        await expect(
            call(() => Promise.resolve({error: {}, response: mockResponse(500)})),
        ).rejects.toMatchObject({status: 500, message: 'Request failed'})
    })

    it('thrown error is instanceof ApiError', async () => {
        let caught: unknown
        try {
            await call(() => Promise.resolve({error: {message: 'oops'}, response: mockResponse(400)}))
        } catch (e) {
            caught = e
        }
        expect(caught).toBeInstanceOf(ApiError)
    })
})

describe('tryCall', () => {
    it('returns ok:true with data on success', async () => {
        const res = await tryCall(() => Promise.resolve({data: [1, 2, 3]}))
        expect(res).toEqual({ok: true, data: [1, 2, 3]})
    })

    it('returns ok:false with error message on SDK error', async () => {
        const res = await tryCall(() =>
            Promise.resolve({
                error: {message: 'Conflict'},
                response: mockResponse(409),
            }),
        )
        expect(res).toEqual({ok: false, error: 'Conflict', status: 409})
    })

    it('returns ok:false when fn throws', async () => {
        const res = await tryCall(() => Promise.reject(new Error('network error')))
        expect(res).toEqual({ok: false, error: 'network error'})
    })

    it('returns ok:false with fallback when thrown value is not Error', async () => {
        const res = await tryCall(() => Promise.reject('some string'))
        expect(res).toEqual({ok: false, error: 'Request failed'})
    })
})
